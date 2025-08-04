package com.madgag.micropython.logiccapture.model

import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectId.zeroId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GitSpecTest extends AnyFlatSpec with Matchers {
  "GitSpec" should "correctly give the https clone url given a git url" in {
    // git://github.com/octocat/hello-world.git
    // git@github.com:rtyley/gha-micropython-logic-capture-workflow.git
    // https://github.com/rtyley/gha-micropython-logic-capture-workflow.git
    GitSpec("git://github.com/octocat/hello-world.git", zeroId).httpsGitUrl shouldBe "https://github.com/octocat/hello-world.git"
  }

  it should "reject clone urls which are not in the correct git-url format" in {
    val sshUrl = "git@github.com:rtyley/gha-micropython-logic-capture-workflow.git"
    assertThrows[IllegalArgumentException] {
      GitSpec(sshUrl, zeroId)
    }
  }
}