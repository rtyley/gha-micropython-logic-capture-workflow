package com.madgag.micropython.logiccapture.model

import com.madgag.micropython.logiccapture.model.GitSpec.httpsGitUrlFor
import org.eclipse.jgit.transport.URIish
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.*
import sttp.model.Uri.*

class GitSpecTest extends AnyFlatSpec with Matchers {
  "GitSpec" should "correctly give the https clone url given a git url" in {
    val httpsGitUri = uri"https://github.com/rtyley/gha-micropython-logic-capture-workflow.git"

    // Local dev, using SSH to clone the repo:
    httpsGitUrlFor(new URIish("git@github.com:rtyley/gha-micropython-logic-capture-workflow.git")) shouldBe httpsGitUri

    // GitHub Actions, using actions/checkout to clone the repo: eg as seen at https://github.com/rtyley/gha-micropython-logic-capture-workflow/actions/runs/18840309511/job/53750790552#step:7:34
    httpsGitUrlFor(new URIish("https://github.com/rtyley/gha-micropython-logic-capture-workflow")) shouldBe httpsGitUri
  }

//  it should "reject clone urls which are not in the correct git-url format" in {
//    val sshUrl = "git@github.com:rtyley/gha-micropython-logic-capture-workflow.git"
//    assertThrows[IllegalArgumentException] {
//      GitSpec(sshUrl, zeroId)
//    }
//  }
}