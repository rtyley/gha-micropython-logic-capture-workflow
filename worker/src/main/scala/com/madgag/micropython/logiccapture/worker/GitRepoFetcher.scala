package com.madgag.micropython.logiccapture.worker

import com.madgag.micropython.logiccapture.model.GitSource
import cats.*
import cats.data.*
import cats.effect.IO
import cats.syntax.all.*
import com.madgag.micropython.logiccapture.logTime
import com.madgag.micropython.logiccapture.model.GitSource.authWith
import com.madgag.micropython.logiccapture.model.GitSpec.refSpecToFetch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.transport.URIish

object GitRepoFetcher {
  def fetch(gitSource: GitSource, repoContainerDir: os.Path): IO[os.Path] = IO.blocking {
    val cloneableUrl = gitSource.gitSpec.httpsGitUrl

    val repoDir = repoContainerDir / "repo"

    println(s"going to try to clone '$cloneableUrl' to $repoDir")

    val git = Git.init().setDirectory(repoDir.toIO).call()
    git.remoteAdd().setName("origin").setUri(new URIish(cloneableUrl.toString)).call()

    val commitId = gitSource.gitSpec.commitId
    println(commitId)

    val fetchResult = git.fetch().authWith(gitSource).setRefSpecs(refSpecToFetch(commitId)).call()

    println(s"fetchResult=$fetchResult")

    git.checkout().setName(commitId.name()).call()

    val repository = git.getRepository.asInstanceOf[FileRepository]

    repoDir
  }.logTime("Cloning repo")
}
