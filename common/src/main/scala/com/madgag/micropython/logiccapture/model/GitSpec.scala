package com.madgag.micropython.logiccapture.model

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{Constants, ObjectId}
import org.eclipse.jgit.transport.URIish
import os.SubPath
import sttp.model.Uri
import upickle.default.*

import java.io.File
import scala.jdk.CollectionConverters.*

given ReadWriter[ObjectId] = readwriter[String].bimap[ObjectId](_.name, ObjectId.fromString)
given ReadWriter[Uri] = readwriter[String].bimap[Uri](_.toString, Uri.unsafeParse)

object GitSpec {
  def forPathInThisRepo(path: os.SubPath): GitSpec = {
    extension (paths: java.util.Set[String])
      private def relevantPaths: Set[SubPath] =
        paths.asScala.toSet.map(f => SubPath(f.split('/').toIndexedSeq)).filter(_.startsWith(path))

    val git = Git.open(new File(""))
    {
      val status = git.status().call()
      val uncommitted = status.getUncommittedChanges.relevantPaths
      val untracked = status.getUntracked.relevantPaths
      require(uncommitted.isEmpty)
      require(untracked.isEmpty)
    }

    val remoteUris = git.remoteList().call().asScala.head.getURIs.asScala
    println(remoteUris)
    val headCommit = git.getRepository.resolve(Constants.HEAD)
    println(headCommit)
    GitSpec(httpsGitUrlFor(remoteUris.head), headCommit)
  }
  
  def httpsGitUrlFor(githubURI: URIish): Uri = {
    val uri = Uri.apply(githubURI.getHost).scheme("https").withWholePath(githubURI.getPath)
    if (uri.path.last.endsWith(".git")) uri else uri.withWholePath(uri.pathToString + ".git")
  }
}

case class GitSpec(httpsGitUrl: Uri, commitId: ObjectId) derives ReadWriter {
  require(httpsGitUrl.scheme.contains("https"))
  
  // require(gitUrl.startsWith("git://") && gitUrl.endsWith(".git"))

  // val httpsGitUrl: String = "https" + gitUrl.stripPrefix("git")
}