package com.gu.snippets.snippet


import net.liftweb.http.rest.{RestContinuation, RestHelper}
import com.gu.snippets.model._
import net.liftweb.json.Extraction
import net.liftweb.common.{Box, Loggable}
import net.liftweb.json.JsonAST.{JNull, JValue}
import net.liftweb.http._
import net.liftweb.util._
import Helpers._

/** blah */
object SnippetApi extends RestHelper with Loggable {
  def snippetsAsJson(snippets: List[Snippet]) = Extraction.decompose(snippets.map(s => SnippetInfo(s)))

  implicit def snippets2LiftResponse(snippets: List[Snippet]) = JsonResponse(snippetsAsJson(snippets))
  implicit def action2LiftResponse(action: Action) = JsonResponse(action.asJValue)

  def recomposeUrl(parts: List[String]) = parts.reduceLeft { _ + "/" + _ }

  def getArticleID(urlParts: List[String]) = "/" + recomposeUrl(urlParts)

  def ensureExists(jSnippet: JValue): Box[Snippet] = {
    // save snippet if not already in DB
    for {
      snippet <- Snippet.fromJValue(jSnippet)
    } yield {
      if (!Snippet.isCreated_?(snippet)) {
        snippet.save
      }
      snippet
    }
  }

  serve("api" / "snippet" prefix {
    case Nil JsonGet _ => Snippet.findAll

    case "save" :: Nil JsonPost json -> _ => {
      val snippet = ensureExists(json)

      for (snippet <- snippet) yield {
        val previouslyKept = Action.kept_?(snippet)

        previouslyKept.getOrElse({
          snippet.saves.atomicUpdate(_ + 1)
          Action(snippet, ActionType.save)
        })
      }
    }

    case "share" :: Nil JsonPost jSnippet -> _ => {
      val snippet = ensureExists(jSnippet)

      snippet.foreach(_.shares.atomicUpdate(_ + 1))

      snippet.map { snippet => Action(snippet, ActionType.share) }
    }

    case "embed" :: Nil JsonPost jSnippet -> _ => {
      val snippet = ensureExists(jSnippet)

      snippet.foreach(_.embeds.atomicUpdate(_ + 1))

      snippet.map { snippet => Action(snippet, ActionType.embed) }
    }

    case "comment" :: Nil JsonPost jSnippet -> _ => {
      val snippet = ensureExists(jSnippet)

      snippet.foreach(_.comments.atomicUpdate(_ + 1))

      snippet.map { snippet => Action(snippet, ActionType.comment) }
    }

    case "article" :: articleID JsonGet _ => {
      Snippet.forArticle(getArticleID(articleID))
    }

    case "actions" :: Nil JsonGet _ => {
      val actions = Action.all
      val snippetsByRef = Snippet.all.groupBy(_.reference.get)

      Extraction.decompose(for {
        action <- actions
        snippet <- snippetsByRef(action.reference.get)
      } yield {
        SnippetUpdate(snippet, action)
      })
    }

    case "actions" :: articleIDParts JsonGet _ => {
      val articleID = getArticleID(articleIDParts)

      val snippetsByRef = Snippet.forArticle(articleID).groupBy(_.reference.get)

      val actions = Action.forArticle(articleID)

      Extraction.decompose(for {
        action <- actions
        snippet <- snippetsByRef(action.reference.get)
      } yield {
        SnippetUpdate(snippet, action)
      })
    }

    case "poll" :: Nil JsonGet _ => {
      RestContinuation.async {
        satisfyRequest => {
          Schedule.schedule(() => satisfyRequest(JNull), 110 seconds)
        }

        val onCreateCallback: (Action, Snippet) => Boolean = { (action, snippet) =>
          satisfyRequest(Extraction.decompose(SnippetUpdate(snippet, action)))
          true
        }

        Action.onCreate(onCreateCallback)
      }
    }

    case "poll" :: articleIDParts JsonGet _ => {
      val articleID = getArticleID(articleIDParts)

      RestContinuation.async {
        satisfyRequest => {
          Schedule.schedule(() => satisfyRequest(JNull), 110 seconds)
        }

        val onCreateCallback: (Action, Snippet) => Boolean = { (action, snippet) =>
          if (action.articleID.get == articleID) {
            satisfyRequest(Extraction.decompose(SnippetUpdate(snippet, action)))
            true
          } else {
            false
          }
        }

        Action.onCreate(onCreateCallback)
      }
    }
  })
}
