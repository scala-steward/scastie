package com.olegych.scastie.client
package components

import com.olegych.scastie.api

import codemirror.{
  CodeMirror,
  Hint,
  HintConfig,
  LineWidget,
  TextAreaEditor,
  TextMarker,
  TextMarkerOptions,
  Editor => CodeMirrorEditor2,
}

import codemirror.CodeMirror.{Pos => CMPosition}

import japgolly.scalajs.react._, vdom.all._, extra._

import extra.{Reusability, StateSnapshot}
import org.scalajs.dom
import org.scalajs.dom.ext.KeyCode
import org.scalajs.dom.raw.{
  HTMLDivElement,
  HTMLElement,
  HTMLPreElement,
  HTMLTextAreaElement
}
import org.scalajs.dom.console

import scala.scalajs._

final case class Editor(isDarkTheme: Boolean,
                        isPresentationMode: Boolean,
                        isEmbedded: Boolean,
                        showLineNumbers: Boolean,
                        code: String,
                        attachedDoms: AttachedDoms,
                        instrumentations: Set[api.Instrumentation],
                        compilationInfos: Set[api.Problem],
                        runtimeError: Option[api.RuntimeError],
                        completions: List[api.Completion],
                        typeAtInfo: Option[api.TypeInfoAt],
                        codeFolds: Set[RangePosititon],
                        run: Reusable[Callback],
                        saveOrUpdate: Reusable[Callback],
                        clear: Reusable[Callback],
                        openNewSnippetModal: Reusable[Callback],
                        toggleHelp: Reusable[Callback],
                        toggleConsole: Reusable[Callback],
                        toggleWorksheetMode: Reusable[Callback],
                        toggleTheme: Reusable[Callback],
                        toggleLineNumbers: Reusable[Callback],
                        togglePresentationMode: Reusable[Callback],
                        formatCode: Reusable[Callback],
                        codeChange: String ~=> Callback,
                        completeCodeAt: Int ~=> Callback,
                        requestTypeAt: (String, Int) ~=> Callback,
                        clearCompletions: Reusable[Callback]) {
  @inline def render: VdomElement = Editor.component(this)
}

object Editor {

  val editorShouldRefresh: Reusability[Editor] =
    Reusability.byRef ||
      (
        Reusability.by((_: Editor).attachedDoms) &&
          Reusability.by((_: Editor).instrumentations) &&
          Reusability.by((_: Editor).compilationInfos) &&
          Reusability.by((_: Editor).runtimeError) &&
          Reusability.by((_: Editor).completions) &&
          Reusability.by((_: Editor).codeFolds)
      )

  implicit val reusability: Reusability[Editor] =
    Reusability.caseClass[Editor]

  implicit val problemAnnotationsReuse
    : Reusability[Map[api.Problem, Annotation]] =
    Reusability((a, b) => a.keys == b.keys)

  implicit val renderAnnotationsReuse
    : Reusability[Map[api.Instrumentation, Annotation]] =
    Reusability((a, b) => a.keys == b.keys)

  implicit val runtimeErrorAnnotationsReuse
    : Reusability[Map[api.RuntimeError, Annotation]] =
    Reusability((a, b) => a.keys == b.keys)

  implicit val codeFoldsReuse
    : Reusability[Map[RangePosititon, Annotation]] =
    Reusability((a, b) => a.keys == b.keys)


  implicit val completionStateReuse: Reusability[CompletionState] =
    Reusability.byRefOr_==

  implicit val editorStateReuse: Reusability[EditorState] =
    Reusability.caseClassExcept[EditorState]('editor)

  // For Scala.js bundler
  codemirror.Comment
  codemirror.Dialog
  codemirror.CloseBrackets
  codemirror.MatchBrackets
  codemirror.BraceFold
  codemirror.FoldCode
  codemirror.Search
  codemirror.SearchCursor
  codemirror.HardWrap
  codemirror.ShowHint
  codemirror.RunMode
  codemirror.SimpleScrollBars
  codemirror.MatchHighlighter
  codemirror.Sublime
  codemirror.CLike

  // CodeMirror.keyMap.sublime -= "Ctrl-L"

  private var codemirrorTextarea: HTMLTextAreaElement = _

  private object loadingMessage {
    private val message = {
      val ul = dom.document
        .createElement("ul")
        .asInstanceOf[HTMLElement]
      ul.className = ul.className.concat(" CodeMirror-hints loading-message")
      ul.style.opacity = "0"

      val li = dom.document.createElement("li").asInstanceOf[HTMLElement]
      li.className = li.className.concat("CodeMirror-hint")

      val span = dom.document.createElement("span").asInstanceOf[HTMLElement]
      span.className = span.className.concat("cm-def")
      span.innerHTML = "Loading..."

      li.appendChild(span)
      ul.appendChild(li)

      ul
    }

    def hide(): Unit = {
      message.style.opacity = "0"
    }

    def show(editor: codemirror.Editor,
             pos: codemirror.CodeMirror.Position): Unit = {

      editor.addWidget(pos, message, scrollIntoView = true)
      message.style.opacity = "1"
    }
  }

  private object hoverMessage {
    private val message = dom.document
      .createElement("div")
      .asInstanceOf[HTMLDivElement]

    private val tooltip =
      dom.document.createElement("div").asInstanceOf[HTMLDivElement]
    tooltip.className = tooltip.className.concat(" CodeMirror-hover-tooltip")
    tooltip.appendChild(message)
    dom.document.body.appendChild(tooltip)

    private var node: Option[HTMLElement] = None

    def hideTooltip(e: dom.Event): Unit = {
      CodeMirror.off(dom.document, "mouseout", hideTooltip)
      if (node.isDefined)
        node.get.className = node.get.className.replace(" CodeMirror-hover", "")

      if (tooltip.parentNode != null) {
        tooltip.style.opacity = "0"
        ()
      }
    }

    def position(e: dom.MouseEvent): Unit = {
      if (tooltip.style.opacity == null) {
        CodeMirror.off(dom.document, "mousemove", position)
      }
      tooltip.style.top = Math
        .max(0, e.clientY - tooltip.offsetHeight - 5) + "px"
      tooltip.style.left = (e.clientX + 5) + "px"
    }

    def show(nodeElement: HTMLElement, messageString: String): Unit = {
      node = Some(nodeElement)
      node.get.className = node.get.className.concat(" CodeMirror-hover")
      message.innerHTML = messageString
      CodeMirror.on(dom.document, "mousemove", position)
      CodeMirror.on(dom.document, "mouseout", hideTooltip)
      if (tooltip.style.opacity != null) {
        tooltip.style.opacity = "1"
      }
    }

    def updateMessage(messageString: String) = {
      message.innerHTML = messageString
    }
  }

  private[Editor] sealed trait Annotation {
    def clear(): Unit
  }

  private[Editor] case class Line(lw: LineWidget) extends Annotation {
    def clear(): Unit = lw.clear()
  }

  private[Editor] case class Marked(tm: TextMarker) extends Annotation {
    def clear(): Unit = tm.clear()
  }

  private[Editor] case object Empty extends Annotation {
    def clear(): Unit = ()
  }

  /**
   *    +------------------------+-------------+
   *    v                        |             |
   *   Idle --> Requested --> Active <--> NeedRender
   *    ^           |
   *    +-----------+
   *   only if exactly one
   *   completion returned
   */
  private[Editor] sealed trait CompletionState
  private[Editor] case object Idle extends CompletionState
  private[Editor] case object Requested extends CompletionState
  private[Editor] case object Active extends CompletionState
  private[Editor] case object NeedRender extends CompletionState

  private[Editor] case class EditorState(
      editor: Option[TextAreaEditor] = None,
      problemAnnotations: Map[api.Problem, Annotation] = Map(),
      renderAnnotations: Map[api.Instrumentation, Annotation] = Map(),
      runtimeErrorAnnotations: Map[api.RuntimeError, Annotation] = Map(),
      codeFoldsAnnotations: Map[RangePosititon, Annotation] = Map(),
      completionState: CompletionState = Idle,
      showTypeButtonPressed: Boolean = false,
      typeAt: Option[api.TypeInfoAt] = None
  )

  private[Editor] class EditorBackend(
      scope: BackendScope[Editor, EditorState]
  ) {

    def stop(): Callback = {
      scope.modState { s =>
        s.editor.map(_.toTextArea())
        s.copy(editor = None)
      }
    }

    private def options(props: Editor): codemirror.Options = {
      val theme =
        if (props.isDarkTheme) "dark"
        else "light"

      val ctrl =
        if (isMac) "Cmd"
        else "Ctrl"

      type CME = CodeMirrorEditor2

      val highlightSelectionMatches =
        js.Dictionary(
          "showToken" -> js.Dynamic.global.RegExp("\\w")
        )

      def command(f: => Unit): js.Function1[CodeMirrorEditor2, Unit] = {
        ((editor: CodeMirrorEditor2) => f)
      }

      def commandE(
          f: CodeMirrorEditor2 => Unit
      ): js.Function1[CodeMirrorEditor2, Unit] = {
        ((editor: CodeMirrorEditor2) => f(editor))
      }

      def autocomplete(editor: CodeMirrorEditor2): Unit = {
        if (!props.isEmbedded) {
          val doc = editor.getDoc()
          val pos = doc.indexFromPos(doc.getCursor())
          props.clearCompletions.runNow()
          props.completeCodeAt(pos).runNow()

          if (scope.state.runNow().completionState == Idle) {
            loadingMessage.show(editor, doc.getCursor())
          }
          scope.modState(_.copy(completionState = Requested)).runNow()
        }
      }

      js.Dictionary[Any](
          "mode" -> "text/x-scala",
          "autofocus" -> !props.isEmbedded,
          "lineNumbers" -> props.showLineNumbers,
          "lineWrapping" -> false,
          "tabSize" -> 2,
          "indentWithTabs" -> false,
          "theme" -> s"solarized $theme",
          "smartIndent" -> true,
          "keyMap" -> "sublime",
          "scrollPastEnd" -> false,
          "scrollbarStyle" -> "simple",
          "autoCloseBrackets" -> true,
          "matchBrackets" -> true,
          "showCursorWhenSelecting" -> true,
          "highlightSelectionMatches" -> highlightSelectionMatches,
          "extraKeys" -> js.Dictionary(
            "Tab" -> "defaultTab",
            ctrl + "-Enter" -> command(props.run.runNow()),
            ctrl + "-S" -> command(props.saveOrUpdate.runNow()),
            ctrl + "-M" -> command(props.openNewSnippetModal.runNow()),
            "Ctrl" + "-Space" -> commandE { editor =>
              autocomplete(editor)
            },
            "." -> commandE { editor =>
              editor.getDoc().replaceSelection(".")
              props.codeChange(editor.getDoc().getValue()).runNow()
              autocomplete(editor)
            },
            "Esc" -> command(props.clear.runNow()),
            "F1" -> command(props.toggleHelp.runNow()),
            "F2" -> command(props.toggleTheme.runNow()),
            "F3" -> command(props.toggleConsole.runNow()),
            "F4" -> command(props.toggleWorksheetMode.runNow()),
            "F6" -> command(props.formatCode.runNow()),
            "F7" -> command(props.toggleLineNumbers.runNow()),
            "F8" -> command {
              if (!props.isEmbedded) {
                props.togglePresentationMode.runNow()
                if (!props.isPresentationMode) {
                  dom.window
                    .alert("Press F8 again to leave the presentation mode")
                }
              }
            }
          )
        )
        .asInstanceOf[codemirror.Options]
    }

    def start(): Callback = {
      scope.props.flatMap { props =>
        val editor =
          codemirror.CodeMirror.fromTextArea(
            codemirrorTextarea,
            options(props)
          )

        editor.onFocus(_.refresh())

        editor.onChanges(
          (e, _) => props.codeChange(e.getDoc().getValue()).runNow()
        )

        // don't show completions if cursor moves to some other place
        editor.onMouseDown(
          (_, _) => {
            loadingMessage.hide()
            scope.modState(_.copy(completionState = Idle)).runNow()
          }
        )

        editor.onKeyUp((_, e) => {
          scope
            .modState(
              s =>
                s.copy(
                  showTypeButtonPressed = s.showTypeButtonPressed && e.keyCode != KeyCode.Ctrl
              )
            )
            .runNow()
        })

        val terminateKeys = Set(
          KeyCode.Space,
          KeyCode.Escape,
          KeyCode.Enter,
          KeyCode.Up,
          KeyCode.Down
        )

        editor.onKeyDown(
          (_, e) => {
            scope
              .modState(
                s => {
                  var resultState = s

                  resultState = resultState
                    .copy(showTypeButtonPressed = e.keyCode == KeyCode.Ctrl)

                  // if any of these keys are pressed
                  // then user doesn't need completions anymore
                  if (terminateKeys.contains(e.keyCode) && !e.ctrlKey) {
                    loadingMessage.hide()
                    resultState = resultState.copy(completionState = Idle)
                  }

                  // we still show completions but user pressed a key,
                  // => render completions again (filters may apply there)
                  if (resultState.completionState == Active) {
                    // we might need to fetch new completions
                    // when user goes backwards
                    if (e.keyCode == KeyCode.Backspace) {
                      val doc = editor.getDoc()
                      val pos = doc.indexFromPos(doc.getCursor()) - 1
                      props.completeCodeAt(pos).runNow()
                    }

                    resultState = resultState.copy(completionState = NeedRender)
                  }

                  resultState
                }
              )
              .runNow()
          }
        )

        CodeMirror.on(
          editor.getWrapperElement(),
          "mousemove",
          (e: dom.MouseEvent) => {

            val node = e.target
            if (node != null && node.isInstanceOf[HTMLElement]) {
              val text = node.asInstanceOf[HTMLElement].textContent
              if (text != null) {

                // request token under the cursor
                val pos = editor.coordsChar(
                  js.Dictionary[Any](
                    "left" -> e.clientX,
                    "top" -> e.clientY
                  ),
                  mode = null
                )
                val currToken = editor.getTokenAt(pos, precise = null).string

                // Request type info only if Ctrl is pressed
                if (currToken == text) {
                  val s = scope.state.runNow()
                  if (s.showTypeButtonPressed) {
                    val lastTypeInfo = s.typeAt
                    val message =
                      if (lastTypeInfo.isEmpty || lastTypeInfo.get.token != currToken) {
                        // if it's the first typeAt request
                        // OR if user's moved on to a new token
                        // then we request new type information with curr token and show "..."
                        props
                          .requestTypeAt(currToken,
                                         editor.getDoc().indexFromPos(pos))
                          .runNow()
                        "..."
                      } else {
                        s.typeAt.get.typeInfo
                      }
                    hoverMessage.show(node.asInstanceOf[HTMLElement], message)
                  }
                }
              }
            }
          }
        )

        val setEditor =
          scope.modState(_.copy(editor = Some(editor)))

        val applyDeltas =
          scope.state.flatMap(
            state =>
              runDelta(editor, f => scope.modState(f), state, None, props)
          )

        val delayedRefresh =
          Callback(
            scalajs.js.timers.setTimeout(0)(
              editor.refresh()
            )
          )

        setEditor >> applyDeltas >> delayedRefresh
      }
    }
  }

  private def setCode2(editor: TextAreaEditor, code: String): Unit = {
    val doc = editor.getDoc()
    val prevScrollPosition = editor.getScrollInfo()
    doc.setValue(code)
    editor.scrollTo(prevScrollPosition.left, prevScrollPosition.top)
  }

  private def runDelta(editor: TextAreaEditor,
                       modState: (EditorState => EditorState) => Callback,
                       state: EditorState,
                       current: Option[Editor],
                       next: Editor): Callback = {
    def setTheme() = {
      if (current.map(_.isDarkTheme) != Some(next.isDarkTheme)) {
        val theme =
          if (next.isDarkTheme) "dark"
          else "light"

        editor.setOption("theme", s"solarized $theme")
      }
    }

    def setLineNumbers() = {
      if (current.map(_.showLineNumbers) != Some(next.showLineNumbers)) {
        editor.setOption("lineNumbers", next.showLineNumbers)
      }
    }

    def setCode() = {
      if (current.map(_.code) != Some(next.code)) {
        val doc = editor.getDoc()
        if (doc.getValue() != next.code) {
          setCode2(editor, next.code)
        }
      }
    }

    val nl = '\n'
    val modeScala = "text/x-scala"


    val doc = editor.getDoc()

    def fold(startPos: CMPosition,
             endPos: CMPosition,
             content: String,
             process: (HTMLElement => Unit)): Annotation = {
      val node =
        dom.document.createElement("div").asInstanceOf[HTMLDivElement]
      node.className = "fold"
      node.innerHTML = content
      process(node)
      Marked(
        doc.markText(startPos,
                     endPos,
                     js.Dictionary[Any]("replacedWith" -> node)
                       .asInstanceOf[TextMarkerOptions])
      )
    }

    def setRenderAnnotations() = {
      def nextline2(endPos: CMPosition,
                    node: HTMLElement,
                    process: (HTMLElement => Unit),
                    options: js.Any): Annotation = {
        process(node)
        Line(editor.addLineWidget(endPos.line, node, options))
      }

      def nextline(endPos: CMPosition,
                   content: String,
                   process: (HTMLElement => Unit),
                   options: js.Any = null): Annotation = {
        val node =
          dom.document.createElement("pre").asInstanceOf[HTMLPreElement]
        node.className = "line"
        node.innerHTML = content

        nextline2(endPos, node, process, options)
      }

      def inline(startPos: CMPosition,
                 content: String,
                 process: (HTMLElement => Unit)): Annotation = {
        // inspired by blink/devtools WebInspector.JavaScriptSourceFrame::_renderDecorations

        val node =
          dom.document.createElement("pre").asInstanceOf[HTMLPreElement]

        node.className = "inline"

        def updateLeft(editor2: codemirror.Editor): Unit = {
          val doc2 = editor2.getDoc()
          val lineNumber = startPos.line
          doc2.getLine(lineNumber).toOption match {
            case Some(line) =>
              val basePos = new CMPosition { line = lineNumber; ch = 0 }
              val offsetPos = new CMPosition {
                line = lineNumber
                ch = doc2.getLine(lineNumber).map(_.length).getOrElse(0)
              }
              val mode = "local"
              val base = editor2.cursorCoords(basePos, mode)
              val offset = editor2.cursorCoords(offsetPos, mode)
              node.style.left = (offset.left - base.left) + "px"
            case _ =>
              // the line was deleted
              node.innerHTML = null
          }
        }
        updateLeft(editor)
        editor.onChange((editor, _) => updateLeft(editor))

        node.innerHTML = content
        process(node)

        Line(editor.addLineWidget(startPos.line, node, null))
      }

      setAnnotations[api.Instrumentation](
        (props, _) => props.instrumentations,
        {
          case api.Instrumentation(api.Position(start, end),
                                   api.Value(value, tpe)) =>
            val startPos = doc.posFromIndex(start)
            val endPos = doc.posFromIndex(end)

            val process = (node: HTMLElement) => {
              CodeMirror.runMode(s"$value: $tpe", modeScala, node)
              node.title = tpe
              ()
            }
            if (value.contains(nl)) nextline(endPos, value, process)
            else inline(startPos, value, process)
          case api.Instrumentation(api.Position(start, end),
                                   api.Html(content, folded)) => {

            val startPos = doc.posFromIndex(start)
            val endPos = doc.posFromIndex(end)

            val process: (HTMLElement => Unit) = _.innerHTML = content
            if (!folded) nextline(endPos, content, process)
            else fold(startPos, endPos, content, process)
          }
          case instrumentation @ api.Instrumentation(
                api.Position(start, end),
                api.AttachedDom(uuid, folded)
              ) => {

            val startPos = doc.posFromIndex(start)
            val endPos = doc.posFromIndex(end)

            val domNode = next.attachedDoms.get(uuid)

            if (!domNode.isEmpty) {
              val process: (HTMLElement => Unit) = element => {
                domNode.foreach(element.appendChild)
                ()
              }

              if (!folded) nextline(endPos, "", process)
              else fold(startPos, endPos, "", process)

            } else {
              console.log("cannot find dom element uuid: " + uuid)
              Empty
            }
          }
        },
        _.renderAnnotations,
        (state, annotations) => state.copy(renderAnnotations = annotations)
      )

    }

    def setProblemAnnotations() = {
      val doc = editor.getDoc()
      setAnnotations[api.Problem](
        (props, _) => props.compilationInfos,
        info => {
          val line = info.line.getOrElse(0)

          val icon =
            dom.document.createElement("i").asInstanceOf[HTMLDivElement]

          val iconSeverity =
            info.severity match {
              case api.Info    => "fa fa-info"
              case api.Warning => "fa fa-exclamation-triangle"
              case api.Error   => "fa fa-times-circle"
            }

          val classSeverity =
            info.severity match {
              case api.Info    => "info"
              case api.Warning => "warning"
              case api.Error   => "error"
            }

          icon.className = iconSeverity

          val el =
            dom.document.createElement("div").asInstanceOf[HTMLDivElement]
          el.className = s"compilation-info $classSeverity"

          val msg = dom.document.createElement("pre")

          msg.innerHTML = AnsiColorFormatter.formatToHtml(info.message)

          el.appendChild(icon)
          el.appendChild(msg)

          Line(doc.addLineWidget(line - 1, el))
        },
        _.problemAnnotations,
        (state, annotations) => state.copy(problemAnnotations = annotations)
      )
    }

    def setRuntimeErrorAnnotations(): Callback = {
      val doc = editor.getDoc()
      setAnnotations[api.RuntimeError](
        (props, _) => props.runtimeError.toSet,
        runtimeError => {
          val line = runtimeError.line.getOrElse(0)

          val icon =
            dom.document.createElement("i").asInstanceOf[HTMLDivElement]

          icon.className = "fa fa-times-circle"

          val el =
            dom.document.createElement("div").asInstanceOf[HTMLDivElement]
          el.className = "runtime-error"

          val msg = dom.document.createElement("pre")
          msg.textContent = s"""|${runtimeError.message}
                                |
                                |${runtimeError.fullStack}""".stripMargin

          el.appendChild(icon)
          el.appendChild(msg)

          Line(doc.addLineWidget(line - 1, el))
        },
        _.runtimeErrorAnnotations,
        (state, annotations) => state.copy(runtimeErrorAnnotations = annotations)
      )
    }

    def setAnnotations[T](
        fromPropsAndState: (Editor, EditorState) => Set[T],
        annotate: T => Annotation,
        fromState: EditorState => Map[T, Annotation],
        updateState: (EditorState, Map[T, Annotation]) => EditorState
    ): Callback = {

      val currentAnnotations: Set[T] =
        current.map(props => 
          fromPropsAndState(props, state)
        ).getOrElse(Set())

      val nextAnnotations: Set[T] =
        fromPropsAndState(next, state)

      val addedAnnotations: Set[T] =
        nextAnnotations -- currentAnnotations

      val annotationsToAdd: CallbackTo[Map[T, Annotation]] =
        CallbackTo
          .sequence(addedAnnotations.map(item => CallbackTo((item, annotate(item)))))
          .map(_.toMap)

      val removedAnnotations: Set[T] =
        currentAnnotations -- nextAnnotations

      val annotationsToRemove: CallbackTo[Set[T]] =
        CallbackTo.sequence(
          fromState(state)
            .filterKeys(removedAnnotations.contains)
            .map {
              case (item, annot) => CallbackTo({ annot.clear(); item })
            }
            .toSet
        )

      for {
        added <- annotationsToAdd
        removed <- annotationsToRemove
        _ <- 
          modState{state =>
            updateState(state, (fromState(state) ++ added) -- removed)
          }
      } yield ()
    }

    def setCompletions(): Unit = {
      if (state.completionState == Requested ||
          state.completionState == NeedRender ||
          !next.completions.equals(current.getOrElse(next).completions)) {

        loadingMessage.hide()

        val doc = editor.getDoc()
        val cursor = doc.getCursor()
        var fr = cursor.ch
        val to = cursor.ch
        val currLine = cursor.line
        val alphaNum = ('a' to 'z').toSet ++ ('A' to 'Z').toSet ++ ('0' to '9').toSet
        val lineContent = doc.getLine(currLine).getOrElse("")

        var i = fr - 1
        while (i >= 0 && alphaNum.contains(lineContent.charAt(i))) {
          fr = i
          i -= 1
        }

        val currPos = doc.indexFromPos(doc.getCursor())
        val filter = doc
          .getValue()
          .substring(doc.indexFromPos(new CMPosition {
            line = currLine; ch = fr
          }), currPos)

        // stop autocomplete if user reached brackets
        val enteredBrackets =
          doc.getValue().substring(currPos - 1, currPos + 1) == "()" &&
            state.completionState != Requested

        if (enteredBrackets || next.completions.isEmpty) {
          modState(_.copy(completionState = Idle)).runNow()
        } else {
          // autopick single completion only if it's user's first request
          val completeSingle = next.completions.length == 1 && state.completionState == Requested

          CodeMirror.showHint(
            editor,
            (_, options) => {
              js.Dictionary(
                "from" -> new CMPosition {
                  line = currLine; ch = fr
                },
                "to" -> new CMPosition {
                  line = currLine; ch = to
                },
                "list" -> next.completions
                // FIXME: can place not 'important' completions first
                  .filter(_.hint.startsWith(filter))
                  .map {
                    completion =>
                      HintConfig
                        .className("autocomplete")
                        .text(completion.hint)
                        .render(
                          (el, _, _) ⇒ {

                            val hint = dom.document
                              .createElement("span")
                              .asInstanceOf[HTMLPreElement]
                            hint.className = "name cm-def"
                            hint.textContent = completion.hint

                            val signature = dom.document
                              .createElement("pre")
                              .asInstanceOf[HTMLPreElement]
                            signature.className = "signature"

                            CodeMirror.runMode(completion.signature,
                                               modeScala,
                                               signature)

                            val resultType = dom.document
                              .createElement("pre")
                              .asInstanceOf[HTMLPreElement]
                            resultType.className = "result-type"

                            CodeMirror.runMode(completion.resultType,
                                               modeScala,
                                               resultType)

                            el.appendChild(hint)
                            el.appendChild(signature)
                            el.appendChild(resultType)

                            if (next.isPresentationMode) {
                              val hintsDiv =
                                signature.parentElement.parentElement
                              hintsDiv.className = hintsDiv.className
                                .concat(" presentation-mode")
                            }
                          }
                        ): Hint
                  }
                  .to[js.Array]
              )
            },
            js.Dictionary(
              "container" -> dom.document.querySelector(".CodeMirror"),
              "alignWithWord" -> true,
              "completeSingle" -> completeSingle
            )
          )

          modState(_.copy(completionState = Active)).runNow()
          if (completeSingle) {
            modState(_.copy(completionState = Idle)).runNow()
            next.clearCompletions.runNow()
          }
        }
      }
    }

    def setTypeAt(): Unit = {
      if (current.map(_.typeAtInfo) != Some(next.typeAtInfo)) {
        if (next.typeAtInfo.isDefined) {
          hoverMessage.updateMessage(next.typeAtInfo.get.typeInfo)
        }
        modState(_.copy(typeAt = next.typeAtInfo)).runNow()
      }
    }

    def findFolds(code: String): Set[RangePosititon] = {
      val (folds, _, _) = {
        val lines = code.split("\n").toList

        lines.foldLeft((Set.empty[RangePosititon], Option.empty[Int], 0)) {
          case ((folds, open, indexTotal), line) => {
            val (folds0, open0) = 
              if (line == "// fold") {
                if(open.isEmpty) (folds, Some(indexTotal))
                else (folds, open)
              } else if (line == "// end-fold") {
                open match {
                  case Some(start) => 
                    (folds + RangePosititon(start, indexTotal + line.length), None)

                  case None => (folds, None)
                }
              } else {
                (folds, open)
              }

            (folds0, open0, indexTotal + line.length + 1)
          }
        }
      }

      folds
    }

    def setCodeFoldingAnnotations(): Callback = {
      val codeChanged = //false
      current.map(_.code != next.code).getOrElse(true)

      setAnnotations[RangePosititon](
        (props, state) => {
          if(current.contains(props)) {
            // code folds are already calculated
            state.codeFoldsAnnotations.keySet
          } else {
            findFolds(props.code)
          }
        },
        range => {
          val posStart = doc.posFromIndex(range.indexStart)
          val posEnd = doc.posFromIndex(range.indexEnd)

          val noop: (HTMLElement => Unit) = _ => ()

          fold(posStart, posEnd, "folded", noop)
        },
        _.codeFoldsAnnotations,
        (state, annotations) => state.copy(codeFoldsAnnotations = annotations)
      ).when_(codeChanged)

    }

    def refresh(): Unit = {
      val shouldRefresh =
        current.map(c => !editorShouldRefresh.test(c, next)).getOrElse(true)

      if (shouldRefresh) {
        editor.refresh()
      }
    }

    Callback(setTheme()) >>
      Callback(setCode()) >>
      Callback(setLineNumbers()) >>
      setProblemAnnotations() >>
      setRenderAnnotations() >>
      setRuntimeErrorAnnotations >>
      setCodeFoldingAnnotations() >>
      Callback(setCompletions()) >>
      Callback(setTypeAt()) >>
      Callback(refresh())
  }

  private val component =
    ScalaComponent
      .builder[Editor]("Editor")
      .initialState(EditorState())
      .backend(new EditorBackend(_))
      .renderPS {
        case (scope, props, _) =>
          div(cls := "editor-wrapper")(
            textarea.ref(codemirrorTextarea = _)(
              defaultValue := props.code,
              name := "CodeArea",
              autoComplete := "off"
            )
          )
      }
      .componentWillReceiveProps { scope =>
        val current = scope.currentProps
        val next = scope.nextProps
        val state = scope.state

        state.editor
          .map(
            editor =>
              runDelta(editor,
                       (f => scope.modState(f)),
                       state,
                       Some(current),
                       next)
          )
          .getOrElse(Callback.empty)

      }
      .componentDidMount(_.backend.start())
      .componentWillUnmount(_.backend.stop())
      .configure(Reusability.shouldComponentUpdate)
      .build
}
