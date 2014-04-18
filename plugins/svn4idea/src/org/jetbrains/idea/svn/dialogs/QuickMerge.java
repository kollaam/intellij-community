/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.svn.dialogs;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.util.PopupUtil;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.changes.shelf.ShelveChangesManager;
import com.intellij.openapi.vcs.versionBrowser.ChangeBrowserSettings;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vfs.newvfs.RefreshSessionImpl;
import com.intellij.util.Consumer;
import com.intellij.util.FilePathByPathComparator;
import com.intellij.util.PairConsumer;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.continuation.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.NestedCopyType;
import org.jetbrains.idea.svn.SvnBranchConfigurationManager;
import org.jetbrains.idea.svn.SvnUtil;
import org.jetbrains.idea.svn.SvnVcs;
import org.jetbrains.idea.svn.actions.ChangeListsMergerFactory;
import org.jetbrains.idea.svn.commandLine.SvnBindException;
import org.jetbrains.idea.svn.history.SvnChangeList;
import org.jetbrains.idea.svn.history.SvnCommittedChangesProvider;
import org.jetbrains.idea.svn.history.SvnRepositoryLocation;
import org.jetbrains.idea.svn.history.TreeStructureNode;
import org.jetbrains.idea.svn.integrate.*;
import org.jetbrains.idea.svn.mergeinfo.MergeChecker;
import org.jetbrains.idea.svn.mergeinfo.OneShotMergeInfoHelper;
import org.jetbrains.idea.svn.mergeinfo.SvnMergeInfoCache;
import org.jetbrains.idea.svn.update.UpdateEventHandler;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNLogEntryPath;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.internal.util.SVNURLUtil;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class QuickMerge {

  @NotNull private final MergeContext myMergeContext;
  private final Continuation myContinuation;
  private QuickMergeInteraction myInteraction;
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.idea.svn.dialogs.QuickMerge");

  public QuickMerge(@NotNull MergeContext mergeContext) {
    myMergeContext = mergeContext;
    myContinuation = Continuation.createFragmented(mergeContext.getProject(), true);
  }

  private static abstract class BaseMergeTask extends TaskDescriptor {

    @NotNull protected final MergeContext myMergeContext;
    @NotNull protected final QuickMergeInteraction myInteraction;

    public BaseMergeTask(@NotNull MergeContext mergeContext,
                         @NotNull QuickMergeInteraction interaction, String name,
                         @NotNull Where where) {
      super(name, where);
      myMergeContext = mergeContext;
      myInteraction = interaction;
    }

    protected void insertMergeAll(final ContinuationContext context) {
      final List<TaskDescriptor> queue = new ArrayList<TaskDescriptor>();
      insertMergeAll(queue);
      context.next(queue);
    }

    protected void insertMergeAll(final List<TaskDescriptor> queue) {
      queue.add(new LocalChangesPrompt(myMergeContext, myInteraction, true, null, null));
      final MergeAllWithBranchCopyPoint mergeAllExecutor = new MergeAllWithBranchCopyPoint(myMergeContext, myInteraction);
      queue.add(myMergeContext.getVcs().getSvnBranchPointsCalculator()
                  .getFirstCopyPointTask(myMergeContext.getWcInfo().getRepositoryRoot(), myMergeContext.getSourceUrl(),
                                         myMergeContext.getWcInfo().getRootUrl(), mergeAllExecutor));
      queue.add(mergeAllExecutor);
    }

    @CalledInAny
    protected void finishWithError(final ContinuationContext context, final String message, final boolean isError) {
      LOG.info((isError ? "Error: " : "Info: ") + message);
      context.next(new TaskDescriptor(message, Where.AWT) {
        @Override
        public void run(ContinuationContext context) {
          myInteraction.showErrors(message, isError);
          context.cancelEverything();
        }
      });
    }

    @CalledInAny
    protected void finishWithError(final ContinuationContext context, final String message, final List<VcsException> exceptions) {
      if (exceptions != null) {
        for (VcsException exception : exceptions) {
          LOG.info(message, exception);
        }
      }
      context.cancelEverything();
      context.next(new TaskDescriptor(message, Where.AWT) {
        @Override
        public void run(ContinuationContext context) {
          myInteraction.showErrors(message, exceptions);
        }
      });
    }
  }

  private static class SourceUrlCorrection extends BaseMergeTask {

    private SourceUrlCorrection(@NotNull MergeContext mergeContext, @NotNull QuickMergeInteraction interaction) {
      super(mergeContext, interaction, "Checking branch", Where.POOLED);
    }

    @Override
    public void run(ContinuationContext continuationContext) {
      final SVNURL branch =
        SvnBranchConfigurationManager.getInstance(myMergeContext.getProject()).getSvnBranchConfigManager().getWorkingBranchWithReload(
          myMergeContext.getWcInfo().getUrl(), myMergeContext.getRoot());
      if (branch != null && (!myMergeContext.getWcInfo().getUrl().equals(branch))) {
        final String branchString = branch.toString();
        if (SVNPathUtil.isAncestor(branchString, myMergeContext.getWcInfo().getRootUrl())) {
          final String subPath = SVNPathUtil.getRelativePath(branchString, myMergeContext.getWcInfo().getRootUrl());
          myMergeContext.setSourceUrl(SVNPathUtil.append(myMergeContext.getSourceUrl(), subPath));
        }
      }
    }
  }

  private static class MyInitChecks extends BaseMergeTask {

    private MyInitChecks(@NotNull MergeContext mergeContext, @NotNull QuickMergeInteraction interaction) {
      super(mergeContext, interaction, "initial checks", Where.AWT);
    }

    @Override
    public void run(ContinuationContext continuationContext) {
      SVNURL url = parseUrl(continuationContext);
      if (url == null) {
        return;
      }

      if (SVNURLUtil.isAncestor(url, myMergeContext.getWcInfo().getUrl()) ||
          SVNURLUtil.isAncestor(myMergeContext.getWcInfo().getUrl(), url)) {
        finishWithError(continuationContext, "Cannot merge from self", true);
        return;
      }

      if (! checkForSwitchedRoots()) {
        continuationContext.cancelEverything();
      }
    }

    private boolean checkForSwitchedRoots() {
      final List<WCInfo> infoList = myMergeContext.getVcs().getAllWcInfos();
      boolean switchedFound = false;
      for (WCInfo wcInfo : infoList) {
        if (FileUtil.isAncestor(new File(myMergeContext.getWcInfo().getPath()), new File(wcInfo.getPath()), true)
            && NestedCopyType.switched.equals(wcInfo.getType())) {
          switchedFound = true;
          break;
        }
      }
      if (switchedFound) {
        return myInteraction.shouldContinueSwitchedRootFound();
      }
      return true;
    }

    @Nullable
    private SVNURL parseUrl(ContinuationContext continuationContext) {
      SVNURL url = null;

      try {
        url = SvnUtil.createUrl(myMergeContext.getSourceUrl());
      }
      catch (SvnBindException e) {
        finishWithError(continuationContext, e.getMessage(), true);
      }

      return url;
    }
  }

  private static class CheckRepositorySupportsMergeinfo extends BaseMergeTask {

    private CheckRepositorySupportsMergeinfo(@NotNull MergeContext mergeContext, @NotNull QuickMergeInteraction interaction) {
      super(mergeContext, interaction, "Checking repository capabilities", Where.POOLED);
    }

    @Override
    public void run(ContinuationContext context) {
      final List<TaskDescriptor> tasks = new LinkedList<TaskDescriptor>();
      final boolean supportsMergeinfo = myMergeContext.getWcInfo().getFormat().supportsMergeInfo() && SvnUtil.checkRepositoryVersion15(
        myMergeContext.getVcs(), myMergeContext.getSourceUrl());
      if (! supportsMergeinfo) {
        insertMergeAll(tasks);
      } else {
        tasks.add(new MergeAllOrSelectedChooser(myMergeContext, myInteraction));
      }
      context.next(tasks);
    }
  }

  @CalledInAwt
  public void execute(@NotNull final QuickMergeInteraction interaction, @NotNull final TaskDescriptor... finalTasks) {
    myInteraction = interaction;
    myInteraction.setTitle(myMergeContext.getTitle());

    FileDocumentManager.getInstance().saveAllDocuments();

    final List<TaskDescriptor> tasks = new LinkedList<TaskDescriptor>();
    tasks.add(new MyInitChecks(myMergeContext, myInteraction));
    tasks.add(new SourceUrlCorrection(myMergeContext, myInteraction));
    tasks.add(new CheckRepositorySupportsMergeinfo(myMergeContext, myInteraction));
    if (finalTasks.length > 0) {
      tasks.addAll(Arrays.asList(finalTasks));
    }

    myContinuation.addExceptionHandler(VcsException.class, new Consumer<VcsException>() {
      @Override
      public void consume(VcsException e) {
        myInteraction.showErrors(myMergeContext.getTitle(), Collections.singletonList(e));
      }
    });
    myContinuation.addExceptionHandler(SVNException.class, new Consumer<SVNException>() {
      @Override
      public void consume(SVNException e) {
        myInteraction.showErrors(myMergeContext.getTitle(), Collections.singletonList(new VcsException(e)));
      }
    });
    myContinuation.addExceptionHandler(RuntimeException.class, new Consumer<RuntimeException>() {
      @Override
      public void consume(RuntimeException e) {
        myInteraction.showError(e);
      }
    });
    myContinuation.run(tasks);
  }

  private static class ShowRecentInDialog extends BaseMergeTask {
    private final LoadRecentBranchRevisions myLoader;

    private ShowRecentInDialog(@NotNull MergeContext mergeContext,
                               @NotNull QuickMergeInteraction interaction,
                               LoadRecentBranchRevisions loader) {
      super(mergeContext, interaction, "", Where.AWT);
      myLoader = loader;
    }

    @Override
    public void run(ContinuationContext context) {
      final PairConsumer<Long, MergeDialogI> loader = new PairConsumer<Long, MergeDialogI>() {
        @Override
        public void consume(Long bunchSize, final MergeDialogI dialog) {
          final LoadRecentBranchRevisions loader =
            new LoadRecentBranchRevisions(
              myMergeContext.getBranchName(), dialog.getLastNumber(), myMergeContext.getWcInfo(), myMergeContext.getVcs(),
              myMergeContext.getSourceUrl(), bunchSize.intValue());
          final TaskDescriptor updater = new TaskDescriptor("", Where.AWT) {
            @Override
            public void run(ContinuationContext context) {
              dialog.addMoreLists(loader.getCommittedChangeLists());
              if (loader.isLastLoaded()) {
                dialog.setEverythingLoaded(true);
              }
            }

            @Override
            public void canceled() {
              dialog.addMoreLists(Collections.<CommittedChangeList>emptyList());
              dialog.setEverythingLoaded(true);
            }
          };
          final Continuation fragmented = Continuation.createFragmented(myMergeContext.getProject(), true);
          fragmented.addExceptionHandler(VcsException.class, new Consumer<VcsException>() {
            @Override
            public void consume(VcsException e) {
              PopupUtil.showBalloonForActiveComponent(e.getMessage() == null ? e.getClass().getName() : e.getMessage(), MessageType.ERROR);
            }
          });
          fragmented.run(loader, updater);
        }
      };
      final List<CommittedChangeList> lists = myInteraction.showRecentListsForSelection(myLoader.getCommittedChangeLists(),
                                                                                        myMergeContext.getTitle(), myLoader.getHelper(),
                                                                                        loader, myLoader.isLastLoaded());

      if (lists != null && ! lists.isEmpty()){
          final MergerFactory factory = new ChangeListsMergerFactory(lists) {
            @Override
            public IMerger createMerger(SvnVcs vcs, File target, UpdateEventHandler handler, SVNURL currentBranchUrl, String branchName) {
              return new GroupMerger(vcs, lists, target, handler, currentBranchUrl, branchName, false, false, false);
            }
          };
        // fictive branch point, just for
        final SvnBranchPointsCalculator.BranchCopyData copyData =
          new SvnBranchPointsCalculator.BranchCopyData(myMergeContext.getWcInfo().getUrl().toString(), -1, myMergeContext.getSourceUrl(),
                                                       -1);
        context.next(new LocalChangesPrompt(myMergeContext, myInteraction,
                                            false, lists, new SvnBranchPointsCalculator.WrapperInvertor(false, copyData)
                     ),
                     new MergeTask(myMergeContext, myInteraction, factory, myMergeContext.getTitle()));
      } else {
        context.cancelEverything();
      }
    }
  }

  private static class MergeAllOrSelectedChooser extends BaseMergeTask {
    private MergeAllOrSelectedChooser(@NotNull MergeContext mergeContext, @NotNull QuickMergeInteraction interaction) {
      super(mergeContext, interaction, "merge source selector", Where.AWT);
    }

    @Override
    public void run(final ContinuationContext context) {
      final QuickMergeContentsVariants variant = myInteraction.selectMergeVariant();
      if (QuickMergeContentsVariants.cancel == variant) return;
      if (QuickMergeContentsVariants.all == variant) {
        insertMergeAll(context);
        return;
      }
      if (QuickMergeContentsVariants.showLatest == variant) {
        final LoadRecentBranchRevisions loader =
          new LoadRecentBranchRevisions(myMergeContext.getBranchName(), -1, myMergeContext.getWcInfo(), myMergeContext
            .getVcs(), myMergeContext.getSourceUrl());
        final ShowRecentInDialog dialog = new ShowRecentInDialog(myMergeContext, myInteraction, loader);
        context.next(loader, dialog);
        return;
      }

      final MergeCalculator calculator;
      try {
        calculator = new MergeCalculator(myMergeContext, myInteraction, myMergeContext.getWcInfo(), myMergeContext.getSourceUrl(), myMergeContext.getBranchName()
        );
      }
      catch (VcsException e) {
        finishWithError(context, e.getMessage(), true);
        return;
      }
      context.next(myMergeContext.getVcs().getSvnBranchPointsCalculator()
                     .getFirstCopyPointTask(myMergeContext.getWcInfo().getRepositoryRoot(), myMergeContext.getWcInfo().getRootUrl(),
                                            myMergeContext.getSourceUrl(), calculator), calculator);
    }
  }

  private static class MergeAllWithBranchCopyPoint extends BaseMergeTask
    implements Consumer<TransparentlyFailedValueI<SvnBranchPointsCalculator.WrapperInvertor, VcsException>> {

    private final AtomicReference<TransparentlyFailedValueI<SvnBranchPointsCalculator.WrapperInvertor, VcsException>> myData;

    private MergeAllWithBranchCopyPoint(@NotNull MergeContext mergeContext, @NotNull QuickMergeInteraction interaction) {
      super(mergeContext, interaction, "merge all", Where.AWT);
      myData = new AtomicReference<TransparentlyFailedValueI<SvnBranchPointsCalculator.WrapperInvertor, VcsException>>();
    }

    @Override
    public void consume(TransparentlyFailedValueI<SvnBranchPointsCalculator.WrapperInvertor, VcsException> value) {
      myData.set(value);
    }

    @Override
    public void run(ContinuationContext context) {
      SvnBranchPointsCalculator.WrapperInvertor invertor;
      try {
        final TransparentlyFailedValueI<SvnBranchPointsCalculator.WrapperInvertor, VcsException>
          transparentlyFailedValueI = myData.get();
        if (transparentlyFailedValueI == null) {
          finishWithError(context, "Merge start wasn't found", true);
          return;
        }
        invertor = transparentlyFailedValueI.get();
      }
      catch (VcsException e) {
        finishWithError(context, "Merge start wasn't found", Collections.singletonList(e));
        return;
      }
      if (invertor == null) {
        finishWithError(context, "Merge start wasn't found", true);
        return;
      }
      final boolean reintegrate = invertor.isInvertedSense();
      if (reintegrate && (!myInteraction.shouldReintegrate(myMergeContext.getSourceUrl(), invertor.inverted().getTarget()))) {
        context.cancelEverything();
        return;
      }
      final MergerFactory mergerFactory = createBranchMergerFactory(reintegrate, invertor);

      final String title = "Merging all from " + myMergeContext.getBranchName() + (reintegrate ? " (reintegrate)" : "");
      context.next(new MergeTask(myMergeContext, myInteraction, mergerFactory, title));
    }

    private MergerFactory createBranchMergerFactory(final boolean reintegrate,
                                                    final SvnBranchPointsCalculator.WrapperInvertor invertor) {
      return new MergerFactory() {
        public IMerger createMerger(SvnVcs vcs, File target, UpdateEventHandler handler, SVNURL currentBranchUrl, String branchName) {
          return new BranchMerger(vcs, currentBranchUrl, myMergeContext.getWcInfo().getUrl(), myMergeContext.getWcInfo().getPath(), handler,
                                  reintegrate, myMergeContext.getBranchName(),
                                  reintegrate ? invertor.getWrapped().getTargetRevision() : invertor.getWrapped().getSourceRevision());
        }
      };
    }
  }

  private static class MergeTask extends BaseMergeTask {
    private final MergerFactory myFactory;

    // awt since only a wrapper
    private MergeTask(@NotNull MergeContext mergeContext,
                      @NotNull QuickMergeInteraction interaction, final MergerFactory factory,
                      final String mergeTitle) {
      super(mergeContext, interaction, mergeTitle, Where.AWT);
      myFactory = factory;
    }

    @Override
    public void run(ContinuationContext context) {
      final SVNURL sourceUrlUrl;
      try {
        sourceUrlUrl = SVNURL.parseURIEncoded(myMergeContext.getSourceUrl());
      } catch (SVNException e) {
        finishWithError(context, "Cannot merge: " + e.getMessage(), true);
        return;
      }

      final SvnIntegrateChangesTask task = new SvnIntegrateChangesTask(myMergeContext.getVcs(),
                                                                       new WorkingCopyInfo(myMergeContext.getWcInfo().getPath(), true),
                                                                       myFactory, sourceUrlUrl, getName(), false,
                                                                       myMergeContext.getBranchName());
      final TaskDescriptor taskDescriptor = TaskDescriptor.createForBackgroundableTask(task);
      // merge task will be the next after...
      context.next(taskDescriptor);
      // ... after we create changelist
      createChangelist(context);
    }

    private void createChangelist(final ContinuationPause context) {
      final ChangeListManager listManager = ChangeListManager.getInstance(myMergeContext.getProject());
      String name = myMergeContext.getTitle();
      int i = 1;
      boolean updateDefaultList = false;
      while (true) {
        final LocalChangeList changeList = listManager.findChangeList(name);
        if (changeList == null) {
          final LocalChangeList newList = listManager.addChangeList(name, null);
          listManager.setDefaultChangeList(newList);
          updateDefaultList = true;
          break;
        }
        if (changeList.getChanges().isEmpty()) {
          if (! changeList.isDefault()) {
            listManager.setDefaultChangeList(changeList);
            updateDefaultList = true;
          }
          break;
        }
        name = myMergeContext.getTitle() + " (" + i + ")";
        ++ i;
      }
      if (updateDefaultList) {
        context.suspend();
        listManager.invokeAfterUpdate(new Runnable() {
          public void run() {
            context.ping();
          }
        }, InvokeAfterUpdateMode.BACKGROUND_NOT_CANCELLABLE_NOT_AWT, "", ModalityState.NON_MODAL);
      }
    }
  }

  // true if errors found
  static boolean checkListForPaths(String relativeLocal,
                                   String relativeBranch, Pair<SvnChangeList, TreeStructureNode<SVNLogEntry>> pair) {
    // TODO: Such filtering logic is not clear enough so far (and probably not correct for all cases - for instance when we perform merge
    // TODO: from branch1 to branch2 and have revision which contain merge changes from branch3 to branch1.
    // TODO: In this case paths of child log entries will not contain neither urls from branch1 nor from branch2 - and checkEntry() method
    // TODO: will return true => so such revision will not be used (and displayed) further.

    // TODO: Why do we check entries recursively - we have a revision - set of changes in the "merge from" branch? Why do we need to check
    // TODO: where they came from - we want avoid some circular merges or what? Does subversion itself perform such checks or not?
    final List<TreeStructureNode<SVNLogEntry>> children = pair.getSecond().getChildren();
    boolean localChange = false;
    for (TreeStructureNode<SVNLogEntry> child : children) {
      if (checkForSubtree(child, relativeLocal, relativeBranch)) {
        localChange = true;
        break;
      }
    }
    if (! localChange) {
      // check self
      return checkForEntry(pair.getSecond().getMe(), relativeLocal, relativeBranch);
    }
    return localChange;
  }

  // true if errors found
  private static boolean checkForSubtree(final TreeStructureNode<SVNLogEntry> tree,
                                         String relativeBranch, final String localURL) {
    final LinkedList<TreeStructureNode<SVNLogEntry>> queue = new LinkedList<TreeStructureNode<SVNLogEntry>>();
    queue.addLast(tree);

    while (! queue.isEmpty()) {
      final TreeStructureNode<SVNLogEntry> element = queue.removeFirst();
      ProgressManager.checkCanceled();

      if (checkForEntry(element.getMe(), localURL, relativeBranch)) return true;
      queue.addAll(element.getChildren());
    }
    return false;
  }

  // true if errors found
  // checks if either some changed path is in current branch => treat as local change
  // or if no changed paths in current branch, checks if at least one path in "merge from" branch
  // NOTE: this fails for "merge-source" log entries from other branches - when all changed paths are from some
  // third branch - this logic treats such log entry as local.
  private static boolean checkForEntry(final SVNLogEntry entry, final String localURL, String relativeBranch) {
    boolean atLeastOneUnderBranch = false;
    final Map map = entry.getChangedPaths();
    for (Object o : map.values()) {
      final SVNLogEntryPath path = (SVNLogEntryPath) o;
      if (SVNPathUtil.isAncestor(localURL, path.getPath())) {
        return true;
      }
      if (! atLeastOneUnderBranch && SVNPathUtil.isAncestor(relativeBranch, path.getPath())) {
        atLeastOneUnderBranch = true;
      }
    }
    return ! atLeastOneUnderBranch;
  }

  private static class MergeCalculator extends BaseMergeTask implements
                     Consumer<TransparentlyFailedValueI<SvnBranchPointsCalculator.WrapperInvertor, VcsException>> {
    private final static String ourOneShotStrategy = "svn.quickmerge.oneShotStrategy";
    private final WCInfo myWcInfo;
    private final String mySourceUrl;
    private final String myBranchName;
    private final
    AtomicReference<TransparentlyFailedValueI<SvnBranchPointsCalculator.WrapperInvertor, VcsException>>
      myCopyData;
    private boolean myIsReintegrate;

    private final List<CommittedChangeList> myNotMerged;
    private String myMergeTitle;
    private final MergeChecker myMergeChecker;

    @Override
    public void consume(TransparentlyFailedValueI<SvnBranchPointsCalculator.WrapperInvertor, VcsException> value) {
      myCopyData.set(value);
    }

    private MergeCalculator(@NotNull MergeContext mergeContext,
                            @NotNull QuickMergeInteraction interaction, WCInfo wcInfo,
                            String sourceUrl,
                            String branchName) throws VcsException {
      super(mergeContext, interaction, "Calculating not merged revisions", Where.POOLED);
      myWcInfo = wcInfo;
      mySourceUrl = sourceUrl;
      myBranchName = branchName;
      myNotMerged = new LinkedList<CommittedChangeList>();
      myMergeTitle = "Merge from " + branchName;
//      if (Boolean.TRUE.equals(Boolean.getBoolean(ourOneShotStrategy))) {
      myMergeChecker = new OneShotMergeInfoHelper(myMergeContext.getProject(), myWcInfo, mySourceUrl);
      ((OneShotMergeInfoHelper)myMergeChecker).prepare();
/*      } else {
        myMergeChecker = new BranchInfo.MyMergeCheckerWrapper(myWcInfo.getPath(), new BranchInfo(myVcs, myWcInfo.getRepositoryRoot(),
                                                                                                 myWcInfo.getRootUrl(), mySourceUrl,
                                                                                                 mySourceUrl, myVcs.createWCClient()));
      }*/
      myCopyData = new AtomicReference<TransparentlyFailedValueI<SvnBranchPointsCalculator.WrapperInvertor, VcsException>>();
    }

    //"Calculating not merged revisions"
    @Override
    public void run(ContinuationContext context) {
      SvnBranchPointsCalculator.WrapperInvertor copyDataValue = null;
      try {
        copyDataValue = myCopyData.get().get();
      }
      catch (VcsException e) {
        finishWithError(context, "Merge start wasn't found", Collections.singletonList(e));
        return;
      }
      if (copyDataValue == null) {
        finishWithError(context, "Merge start wasn't found", true);
        return;
      }

      final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
      myIsReintegrate = copyDataValue.isInvertedSense();
      if (!myWcInfo.getFormat().supportsMergeInfo()) return;
      final SvnBranchPointsCalculator.BranchCopyData data = copyDataValue.getTrue();
      final long sourceLatest = data.getTargetRevision();

      final SvnCommittedChangesProvider committedChangesProvider =
        (SvnCommittedChangesProvider)myMergeContext.getVcs().getCommittedChangesProvider();
      final ChangeBrowserSettings settings = new ChangeBrowserSettings();
      settings.CHANGE_AFTER = Long.toString(sourceLatest);
      settings.USE_CHANGE_AFTER_FILTER = true;

      String local = SVNPathUtil.getRelativePath(myWcInfo.getRepositoryRoot(), myWcInfo.getRootUrl());
      final String relativeLocal = (local.startsWith("/") ? local : "/" + local);
      String relativeBranch = SVNPathUtil.getRelativePath(myWcInfo.getRepositoryRoot(), mySourceUrl);
      relativeBranch = (relativeBranch.startsWith("/") ? relativeBranch : "/" + relativeBranch);

      final LinkedList<Pair<SvnChangeList, TreeStructureNode<SVNLogEntry>>> list =
        new LinkedList<Pair<SvnChangeList, TreeStructureNode<SVNLogEntry>>>();
      try {
        committedChangesProvider.getCommittedChangesWithMergedRevisons(settings, new SvnRepositoryLocation(mySourceUrl), 0,
                                                                       new PairConsumer<SvnChangeList, TreeStructureNode<SVNLogEntry>>() {
                                                                         public void consume(SvnChangeList svnList, TreeStructureNode<SVNLogEntry> tree) {
                                                                           indicator.checkCanceled();
                                                                           if (sourceLatest >= svnList.getNumber()) return;
                                                                           list.add(new Pair<SvnChangeList, TreeStructureNode<SVNLogEntry>>(svnList, tree));
                                                                         }
                                                                       });
      }
      catch (VcsException e) {
        finishWithError(context, "Checking revisions for merge fault", Collections.singletonList(e));
      }

      indicator.setText("Checking merge information...");
      // to do not go into file system while asking something on the net
      for (Pair<SvnChangeList, TreeStructureNode<SVNLogEntry>> pair : list) {
        final SvnChangeList svnList = pair.getFirst();
        final SvnMergeInfoCache.MergeCheckResult checkResult = myMergeChecker.checkList(svnList);
        indicator.setText2("Processing revision " + svnList.getNumber());

        if (SvnMergeInfoCache.MergeCheckResult.NOT_MERGED.equals(checkResult)) {
          // additionally check for being 'local'
          boolean localChange = checkListForPaths(relativeLocal, relativeBranch, pair);

          if (! localChange) {
            myNotMerged.add(svnList);
          }
        }
      }

      if (myNotMerged.isEmpty()) {
        finishWithError(context, "Everything is up-to-date", false);
        return;
      }
      context.next(new ShowRevisionSelector(copyDataValue));
    }

    private class ShowRevisionSelector extends TaskDescriptor {
      private final SvnBranchPointsCalculator.WrapperInvertor myCopyPoint;

      private ShowRevisionSelector(SvnBranchPointsCalculator.WrapperInvertor copyPoint) {
        super("show revisions to merge", Where.AWT);
        myCopyPoint = copyPoint;
      }

      @Override
      public void run(ContinuationContext context) {
        final QuickMergeInteraction.SelectMergeItemsResult result = myInteraction.selectMergeItems(myNotMerged, myMergeTitle, myMergeChecker);
        if (QuickMergeContentsVariants.cancel == result.getResultCode()) {
          context.cancelEverything();
          return;
        }
        if (QuickMergeContentsVariants.all == result.getResultCode()) {
          insertMergeAll(context);
        } else {
          final List<CommittedChangeList> lists = result.getSelectedLists();
          if (lists.isEmpty()) return;
          final MergerFactory factory = new ChangeListsMergerFactory(lists) {
            @Override
            public IMerger createMerger(SvnVcs vcs, File target, UpdateEventHandler handler, SVNURL currentBranchUrl, String branchName) {
              return new GroupMerger(vcs, lists, target, handler, currentBranchUrl, branchName, false, false, false);
            }
          };
          context.next(new LocalChangesPrompt(myMergeContext, myInteraction, false, lists, myCopyPoint), new MergeTask(myMergeContext,
                                                                                                                       myInteraction,
                                                                                                                       factory, myMergeTitle
          ));
        }
      }
    }
  }

  private static class LocalChangesPrompt extends BaseMergeTask {
    private final boolean myMergeAll;
    @Nullable private final List<CommittedChangeList> myLists;
    private final SvnBranchPointsCalculator.WrapperInvertor myCopyPoint;

    private LocalChangesPrompt(@NotNull MergeContext mergeContext, @NotNull QuickMergeInteraction interaction, final boolean mergeAll,
                               @Nullable final List<CommittedChangeList> lists,
                               @Nullable SvnBranchPointsCalculator.WrapperInvertor copyPoint) {
      super(mergeContext, interaction, "local changes intersection check", Where.AWT);
      myMergeAll = mergeAll;
      myLists = lists;
      myCopyPoint = copyPoint;
    }

    private static Intersection getMergeAllIntersection(List<LocalChangeList> localChangeLists) {
      final Intersection intersection = new Intersection();

      for (LocalChangeList localChangeList : localChangeLists) {
        final Collection<Change> localChanges = localChangeList.getChanges();
        for (Change localChange : localChanges) {
          intersection.add(localChangeList.getName(), localChangeList.getComment(), localChange);
        }
      }
      return intersection;
    }

    @Nullable
    private File getLocalPath(final String relativeToRepoPath) {
      // from source if not inverted
      final String pathToCheck = SVNPathUtil.append(myMergeContext.getWcInfo().getRepositoryRoot(), relativeToRepoPath);
      final SvnBranchPointsCalculator.BranchCopyData wrapped = myCopyPoint.getWrapped();
      final String relativeInSource =
        SVNPathUtil.getRelativePath(myCopyPoint.isInvertedSense() ? wrapped.getSource() : wrapped.getTarget(), pathToCheck);
      if (StringUtil.isEmptyOrSpaces(relativeInSource)) return null;
      final File local = new File(myMergeContext.getWcInfo().getPath(), relativeInSource);
      return local;
    }

    @Override
    public void run(ContinuationContext context) {
      final Intersection intersection;
      final ChangeListManager listManager = ChangeListManager.getInstance(myMergeContext.getProject());
      final List<LocalChangeList> localChangeLists = listManager.getChangeListsCopy();

      if (myMergeAll) {
        intersection = getMergeAllIntersection(localChangeLists);
      } else {
        intersection = checkIntersection(myLists, localChangeLists);
      }
      if (intersection == null || intersection.getChangesSubset().isEmpty()) return;

      final LocalChangesAction action = myInteraction.selectLocalChangesAction(myMergeAll);
      switch (action) {
        // shelve
        case shelve:
          context.next(new ShelveLocalChanges(myMergeContext, myInteraction, intersection));
          return;
        // cancel
        case cancel:
          context.cancelEverything();
          return;
        // continue
        case continueMerge:
          return;
        // inspect
        case inspect:
          // here's cast is due to generic's bug
          @SuppressWarnings("unchecked") final Collection<Change> changes = (Collection<Change>) intersection.getChangesSubset().values();
          final List<FilePath> paths = ChangesUtil.getPaths(changes);
          Collections.sort(paths, FilePathByPathComparator.getInstance());
          myInteraction.showIntersectedLocalPaths(paths);
          context.cancelEverything();
          return;
        default:
      }
    }

    @Nullable
    private Intersection checkIntersection(@Nullable final List<CommittedChangeList> lists, List<LocalChangeList> localChangeLists) {
      if (lists == null || lists.isEmpty()) {
        return null;
      }
      final Set<FilePath> mergePaths = new HashSet<FilePath>();
      for (CommittedChangeList list : lists) {
        final SvnChangeList svnList = (SvnChangeList)list;
        final List<String> paths = new ArrayList<String>(svnList.getAddedPaths());
        paths.addAll(svnList.getChangedPaths());
        paths.addAll(svnList.getDeletedPaths());
        for (String path : paths) {
          final File localPath = getLocalPath(path);
          if (localPath != null) {
            mergePaths.add(new FilePathImpl(localPath, false));
          }
        }
      }

      final Intersection intersection = new Intersection();
      for (LocalChangeList localChangeList : localChangeLists) {
        final Collection<Change> localChanges = localChangeList.getChanges();

        for (Change localChange : localChanges) {
          final FilePath before = localChange.getBeforeRevision() == null ? null : localChange.getBeforeRevision().getFile();
          final FilePath after = localChange.getAfterRevision() == null ? null : localChange.getAfterRevision().getFile();

          if ((before != null && mergePaths.contains(before)) || (after != null && mergePaths.contains(after))) {
            intersection.add(localChangeList.getName(), localChangeList.getComment(), localChange);
          }
        }
      }
      return intersection;
    }
  }

  private static class ShelveLocalChanges extends BaseMergeTask {
    private final Intersection myIntersection;

    private ShelveLocalChanges(@NotNull MergeContext mergeContext,
                               @NotNull QuickMergeInteraction interaction,
                               final Intersection intersection) {
      super(mergeContext, interaction, "Shelving local changes before merge", Where.POOLED);
      myIntersection = intersection;
    }

    @Override
    public void run(final ContinuationContext context) {
      final MultiMap<String, Change> map = myIntersection.getChangesSubset();
      
      final RefreshSessionImpl session = new RefreshSessionImpl(true, false, new Runnable() {
        public void run() {
          context.ping();
        }
      });

      for (String name : map.keySet()) {
        try {
          final Collection<Change> changes = map.get(name);
          ApplicationManager.getApplication().invokeAndWait(new Runnable() {
              @Override
              public void run() {
                FileDocumentManager.getInstance().saveAllDocuments();
              }
            }, ModalityState.NON_MODAL);
          ShelveChangesManager.getInstance(myMergeContext.getProject()).shelveChanges(changes, myIntersection.getComment(name) +
                                                                                               " (auto shelve before merge)",
                                                                                      true
          );
          session.addAllFiles(ChangesUtil.getFilesFromChanges(changes));
        }
        catch (IOException e) {
          finishWithError(context, e.getMessage(), true);
        }
        catch (VcsException e) {
          finishWithError(context, e.getMessage(), true);
        }
      }
      // first suspend to guarantee stop->then start back sequence
      context.suspend();
      session.launch();
    }
  }

  private static class Intersection {
    private final Map<String, String> myLists;
    private final MultiMap<String, Change> myChangesSubset;

    private Intersection() {
      myLists = new HashMap<String, String>();
      myChangesSubset = new MultiMap<String, Change>();
    }

    public void add(@NotNull final String listName, @Nullable final String comment, final Change change) {
      myChangesSubset.putValue(listName, change);
      final String commentToPut = comment == null ? listName : comment;
      myLists.put(listName, commentToPut);
    }

    public String getComment(final String listName) {
      return myLists.get(listName);
    }

    public MultiMap<String, Change> getChangesSubset() {
      return myChangesSubset;
    }
  }
}
