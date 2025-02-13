package com.olegych.scastie.client.components

import japgolly.scalajs.react._
import vdom.all._
import com.olegych.scastie.client.components.editor.EditorKeymaps

final case class HelpModal(isClosed: Boolean, close: Reusable[Callback]) {
  @inline def render: VdomElement = HelpModal.component(this)
}

object HelpModal {
  implicit val reusability: Reusability[HelpModal] =
    Reusability.derive[HelpModal]

  private def render(props: HelpModal): VdomElement = {
    def generateATag(url: String, text: String) =
      a(href := url, target := "_blank", rel := "nofollow", text)

    val scastieGithub =
      generateATag("https://github.com/scalacenter/scastie", "scalacenter/scastie")

    val sublime = generateATag(
      "https://sublime-text-unofficial-documentation.readthedocs.org/en/latest/reference/keyboard_shortcuts_osx.html",
      "keyboard shortcuts."
    )

    val scalafmtConfiguration =
      generateATag("https://scalameta.org/scalafmt/docs/configuration.html#disabling-or-customizing-formatting", "configuration section")

    val originalScastie =
      generateATag("https://github.com/OlegYch/scastie_old", "GitHub")

    val gitter =
      generateATag("https://gitter.im/scalacenter/scastie", "Gitter")

    Modal(
      title = "Help about Scastie",
      isClosed = props.isClosed,
      close = props.close,
      modalCss = TagMod(),
      modalId = "long-help",
      content = TagMod(
        p(cls := "normal", "Scastie is an interactive playground for Scala with support for sbt configuration."),
        p(cls := "normal", "Scastie editor supports Sublime Text ", sublime),
        h2(s"Save (${EditorKeymaps.saveOrUpdate.getName})"),
        p(
          cls := "normal",
          "Run and save your code."
        ),
        h2(s"New (${EditorKeymaps.openNewSnippetModal.getName})"),
        p(
          cls := "normal",
          "Removes all your code lines from the current editor instance and resets sbt configuration."
        ),
        h2(s"Clear Messages (${EditorKeymaps.clear.getName})"),
        p(
          cls := "normal",
          "Removes all messages from the current editor instance."
        ),
        h2(s"Format (${EditorKeymaps.format.getName})"),
        p(cls := "normal",
          "The code formatting is done by scalafmt. You can configure the formatting with comments in your code. Read the ",
          scalafmtConfiguration),
        h2(s"Worksheet"),
        p(
          cls := "normal",
          """Enabled by default, the Worksheet Mode gives the value and the type of each line of your program. You can also add HTML blocks such as: html "<h1>Hello</h1>".fold to break down your program into various sections."""
        ),
        p(
          cls := "normal",
          "In Worksheet Mode you cannot use packages. If you want to use it, turn off the Mode and add a main method and println statements."
        ),
        h2("Download"),
        p(
          cls := "normal",
          "Create a zip package with sbt configuration for current snippet."
        ),
        h2("Embed"),
        p(
          cls := "normal",
          "Create an url embeddable in external web pages."
        ),
        h2(s"Console (${EditorKeymaps.console.getName})"),
        p(
          cls := "normal",
          "You can see your code's output in the Scastie's console."
        ),
        h2("Build Settings"),
        p(
          cls := "normal",
          "In Build Settings you can change the Scala version and add libraries, choose your desired target and even add your own custom sbt configuration."
        ),
        h2("User's Code Snippets"),
        p(
          cls := "normal",
          "Your saved code fragments will appear here and you'll be able to delete or share them."
        ),
        h2("Feedback"),
        p(cls := "normal", "You can join our ", gitter, " channel and send issues."),
        h2("BuildInfo"),
        p(cls := "normal", "It's available on Github at ")(
          scastieGithub,
          br,
          " License: Apache 2",
        ),
        p(
          cls := "normal",
          "Scastie is an original idea from Aleh Aleshka (OlegYch) "
        )(
          originalScastie
        )
      )
    ).render
  }

  private val component =
    ScalaComponent
      .builder[HelpModal]("HelpModal")
      .render_P(render)
      .configure(Reusability.shouldComponentUpdate)
      .build
}
