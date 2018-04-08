package org.ajoberstar.gradle.git.publish

import java.nio.file.Files

import groovy.transform.PackageScope

import org.ajoberstar.grgit.Grgit
import org.ajoberstar.grgit.operation.FetchOp
import org.ajoberstar.grgit.operation.ResetOp
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Copy
import org.gradle.api.file.FileTree
import org.eclipse.jgit.transport.URIish

class GitPublishPlugin implements Plugin<Project> {
  @PackageScope static final String RESET_TASK = 'gitPublishReset'
  @PackageScope static final String COPY_TASK = 'gitPublishCopy'
  @PackageScope static final String COMMIT_TASK = 'gitPublishCommit'
  @PackageScope static final String PUSH_TASK = 'gitPublishPush'

  @Override
  void apply(Project project) {
    GitPublishExtension extension = project.extensions.create('gitPublish', GitPublishExtension, project)

    // if using the grgit plugin, default to the repo's origin
    project.pluginManager.withPlugin('org.ajoberstar.grgit') {
      // TODO should this be based on tracking branch instead of assuming origin?
      extension.repoUri = project.grgit?.remote?.list()?.find { it.name == 'origin' }?.url
    }

    extension.repoDir = project.file("${project.buildDir}/gitPublish")

    Task reset = createResetTask(project, extension)
    Task copy = createCopyTask(project, extension)
    Task commit = createCommitTask(project, extension)
    Task push = createPushTask(project, extension, commit)
    push.dependsOn commit
    commit.dependsOn copy
    copy.dependsOn reset

    // always close the repo at the end of the build
    project.gradle.buildFinished {
      if (extension.ext.has('repo')) {
        project.logger.info('Closing Git publish repo: {}', extension.repo.repository.rootDir)
        extension.repo.close()
      }
    }
  }

  private Task createResetTask(Project project, GitPublishExtension extension) {
    Task task = project.tasks.create(RESET_TASK)
    task.with {
      group = 'publishing'
      description = 'Prepares a git repo for new content to be generated.'

      // get the repo in place
      doFirst {
        Grgit repo = findExistingRepo(project, extension).orElseGet { freshRepo(extension) }

        // TODO replace with grgit.lsremote when added to Grgit
        def cmd = repo.repository.jgit.lsRemote().setRemote('origin').setHeads(true)
        org.ajoberstar.grgit.auth.TransportOpUtil.configure(cmd, null)
        def branchExists = cmd.call().find { it.name == "refs/heads/${extension.branch}" }

        if (branchExists) {
          // fetch only existing pages branch
          repo.fetch(refSpecs: ["+refs/heads/${extension.branch}:refs/remotes/origin/${extension.branch}"], tagMode: FetchOp.TagMode.NONE)

          // make sure local branch exists and tracks the correct remote branch
          if (repo.branch.list().find { it.name == extension.branch }) {
            // branch already exists, set new startPoint
            repo.branch.change(name: extension.branch, startPoint: "origin/${extension.branch}")
          } else {
            // branch doesn't exist, create
            repo.branch.add(name: extension.branch, startPoint: "origin/${extension.branch}")
          }

          // get to the state the remote has
          repo.clean(directories: true, ignore: false)
          repo.checkout(branch: extension.branch)
          repo.reset(commit: "origin/${extension.branch}", mode: ResetOp.Mode.HARD)
        } else {
          repo.checkout(branch: extension.branch, orphan: true)
        }
        extension.ext.repo = repo
      }
      // clean up unwanted files
      doLast {
        FileTree repoTree = project.fileTree(extension.repoDir)
        FileTree preservedTree = repoTree.matching(extension.preserve)
        FileTree unwantedTree = repoTree.minus(preservedTree).asFileTree
        unwantedTree.visit { details ->
          def file = details.file.toPath()
          if (Files.isRegularFile(file)) {
            Files.delete(file)
          }
        }
        // stage the removals, relying on dirs not being tracked by git
        extension.repo.add(patterns: ['.'], update: true)
      }
    }
    return task
  }

  private Task createCopyTask(Project project, GitPublishExtension extension) {
    Task task = project.tasks.create(COPY_TASK)
    task.with {
      group = 'publishing'
      description = 'Copy contents to be published to git.'
      doLast {
        project.copy {
          with extension.contents
          into { extension.repoDir }
        }
      }
    }
    return task
  }

  private Task createCommitTask(Project project, GitPublishExtension extension) {
    Task task = project.tasks.create(COMMIT_TASK)
    task.with {
      group = 'publishing'
      description = 'Commits changes to be published to git.'
      doLast {
        Grgit repo = extension.repo
        repo.add(patterns: ['.'])
        // check if anything has changed
        if (repo.status().clean) {
          didWork = false
        } else {
          repo.commit(message: extension.commitMessage)
          didWork = true
        }
      }
    }
    return task
  }

  private Task createPushTask(Project project, GitPublishExtension extension, Task commit) {
    Task task = project.tasks.create(PUSH_TASK)
    task.with {
      group = 'publishing'
      description = 'Pushes changes to git.'
      // if we didn't commit anything, don't push anything
      onlyIf { commit.didWork }
      doLast {
        extension.repo.push()
      }
    }
    return task
  }

  private Optional<Grgit> findExistingRepo(Project project, GitPublishExtension extension) {
    try {
      Optional.of(Grgit.open(dir: extension.repoDir))
        .filter { repo ->
          String originUri = repo.remote.list().find { it.name == 'origin' }?.url
          // need to use the URIish to normalize them and ensure we support all Git compatible URI-ishs (URL is too limiting)
          boolean valid = new URIish(extension.repoUri) == new URIish(originUri) && extension.branch == repo.branch.current.name
          if (!valid) { repo.close() }
          return valid
        }
    } catch (Exception e) {
      // missing, invalid, or corrupt repo
      project.logger.debug('Failed to find existing Git publish repository.', e)
      return Optional.empty()
    }
  }

  private Grgit freshRepo(GitPublishExtension extension) {
    if (!extension.repoDir.deleteDir()) {
      throw new GradleException("Failed to clean up repo dir: ${extension.repoDir}")
    }
    Grgit repo = Grgit.init(dir: extension.repoDir)
    repo.remote.add(name: 'origin', url: extension.repoUri)
    return repo
  }
}
