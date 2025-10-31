package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.effect.IO
import com.madgag.micropython.logiccapture.model.{GitSource, GitSpec}
import org.eclipse.jgit.lib.ObjectId
import sttp.model.Uri.*
import weaver.SimpleIOSuite

object GitRepoFetcherTest extends SimpleIOSuite {

//  test("be cheerful") {
//    val container = os.temp.dir()
//    val collPlusGitSource = GitSource("", GitSpec(
//      uri"https://github.com/rtyley/scala-collection-plus.git",
//      ObjectId.fromString("1e0d758af497ed910dde344e45f64296826a8297")
//    ))
//    val analogueSource = GitSource("", GitSpec(
//      uri"https://github.com/rtyley/analogue-led-clock.git",
//      ObjectId.fromString("34f7b180f2a1ef235dbb93ad06ecc26a919fb420")
//    ))
//
//    for {
//      repoPath <- GitRepoFetcher.fetch(analogueSource, container)
//      _ <- IO.println(repoPath.toString)
//    } yield expect(clue(repoPath).toIO.exists())
//  }
}
