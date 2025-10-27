package com.madgag.micropython.logiccapture.model

import upickle.default.*

case class GitSource(githubToken: String, gitSpec: GitSpec) derives ReadWriter
