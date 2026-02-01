package com.madgag.micropython.logiccapture.model

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{Constants, ObjectId}
import org.eclipse.jgit.transport.{RefSpec, URIish}
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
      require(uncommitted.isEmpty, s"Uncommited files under $path: $uncommitted")
      require(untracked.isEmpty, s"Untracked files under $path: $untracked")
    }

    val remoteUris = git.remoteList().call().asScala.head.getURIs.asScala
    val headCommit = git.getRepository.resolve(Constants.HEAD)
    println(s"headCommit = ${headCommit.name()}")
    GitSpec(httpsGitUrlFor(remoteUris.head), headCommit)
  }
  
  def httpsGitUrlFor(githubURI: URIish): Uri = {
    val uri = Uri.apply(githubURI.getHost).scheme("https").withWholePath(githubURI.getPath)
    if (uri.path.last.endsWith(".git")) uri else uri.withWholePath(uri.pathToString + ".git")
  }

  // git -c protocol.version=2 fetch --no-tags --prune --no-recurse-submodules --depth=1 origin +ff60d3969262bc1c9b2df6b5989517fa08c463e7:refs/remotes/pull/1/merge

  /**
   * See also https://github.com/actions/checkout/blob/ff7abcd0c3c05ccf6adc123a8cd1fd4fb30fb493/src/ref-helper.ts#L87-L106
   */
  def refSpecToFetch(commitId: ObjectId): RefSpec = new RefSpec(commitId.name())
}

case class GitSpec(httpsGitUrl: Uri, commitId: ObjectId) derives ReadWriter {
  require(httpsGitUrl.scheme.contains("https"))
  
  // require(gitUrl.startsWith("git://") && gitUrl.endsWith(".git"))

  // val httpsGitUrl: String = "https" + gitUrl.stripPrefix("git")
}
