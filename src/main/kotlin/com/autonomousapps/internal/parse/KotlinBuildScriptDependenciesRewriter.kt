package com.autonomousapps.internal.parse

import cash.grammar.kotlindsl.model.DependencyDeclaration
import cash.grammar.kotlindsl.parse.KotlinParseException
import cash.grammar.kotlindsl.parse.Parser
import cash.grammar.kotlindsl.parse.Rewriter
import cash.grammar.kotlindsl.utils.Blocks.isBuildscript
import cash.grammar.kotlindsl.utils.Blocks.isDependencies
import cash.grammar.kotlindsl.utils.CollectingErrorListener
import cash.grammar.kotlindsl.utils.Context.leafRule
import cash.grammar.kotlindsl.utils.DependencyExtractor
import cash.grammar.kotlindsl.utils.Whitespace
import com.autonomousapps.exception.BuildScriptParseException
import com.autonomousapps.internal.advice.AdvicePrinter
import com.autonomousapps.internal.utils.filterToOrderedSet
import com.autonomousapps.internal.utils.filterToSet
import com.autonomousapps.internal.utils.ifNotEmpty
import com.autonomousapps.model.Advice
import com.squareup.cash.grammar.KotlinParser.NamedBlockContext
import com.squareup.cash.grammar.KotlinParser.PostfixUnaryExpressionContext
import com.squareup.cash.grammar.KotlinParser.ScriptContext
import com.squareup.cash.grammar.KotlinParserBaseListener
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CommonTokenStream
import java.nio.file.Path

/**
 * Rewrites a Kotlin build script based on provided advice, which includes:
 *
 * - When entering the `dependencies` block, processing each dependency declaration to either remove or update it according to the advice provided.
 * - Upon exiting the `dependencies` block, adding any new dependencies.
 * - At the end of the script, adding a `dependencies` block if new dependencies are present and the block is currently missing.
 *
 * Note: Buildscript dependencies are ignored.
 */
internal class KotlinBuildScriptDependenciesRewriter(
  private val input: CharStream,
  private val tokens: CommonTokenStream,
  private val errorListener: CollectingErrorListener,
  private val advice: Set<Advice>,
  private val printer: AdvicePrinter,
  /** Reverse map from custom representation to standard. */
  private val reversedDependencyMap: (String) -> String,
) : BuildScriptDependenciesRewriter, KotlinParserBaseListener() {
  private val rewriter = Rewriter(tokens)
  private val indent = Whitespace.computeIndent(tokens, input)

  private val dependencyExtractor = DependencyExtractor(
    input = input,
    tokens = tokens,
    indent = indent,
  )

  private var hasDependenciesBlock = false
  private var inBuildscriptBlock = false

  /**
   * Returns build script with advice applied.
   *
   * Throws [BuildScriptParseException] if the script has some idiosyncrasy that impairs parsing.
   *
   */
  @Throws(BuildScriptParseException::class)
  override fun rewritten(): String {
    errorListener.getErrorMessages().ifNotEmpty {
      throw BuildScriptParseException.withErrors(it)
    }

    return rewriter.text
  }

  override fun enterNamedBlock(ctx: NamedBlockContext) {
    dependencyExtractor.onEnterBlock()

    if (ctx.isBuildscript) {
      inBuildscriptBlock = true
    }

    if (ctx.isDependencies) {
      handleDependencies(ctx)
    }
  }

  override fun exitNamedBlock(ctx: NamedBlockContext) {
    dependencyExtractor.onExitBlock()

    if (ctx.isDependencies && !inBuildscriptBlock) {
      hasDependenciesBlock = true
      advice.filterToSet { it.isAnyAdd() }.ifNotEmpty { addAdvice ->
        val closeBrace = ctx.stop
        rewriter.insertBefore(closeBrace, addAdvice.joinToString(separator = "\n", postfix = "\n") { a ->
          printer.toDeclaration(a)
        })
      }
    }

    // Must be last
    if (ctx.isBuildscript) {
      inBuildscriptBlock = false
    }
  }

  override fun exitScript(ctx: ScriptContext) {
    // Exit early if this build script has a dependencies block. If it doesn't, we may need to add missing dependencies.
    if (hasDependenciesBlock) return

    advice.filterToOrderedSet { it.isAnyAdd() }.ifNotEmpty { addAdvice ->
      rewriter.insertBefore(
        ctx.stop,
        addAdvice.joinToString(prefix = "\ndependencies {\n", postfix = "\n}\n", separator = "\n") { a ->
          printer.toDeclaration(a)
        }
      )
    }
  }

  private fun handleDependencies(ctx: NamedBlockContext) {
    if (inBuildscriptBlock) return

    val dependencyContainer = dependencyExtractor.collectDependencies(ctx)
    dependencyContainer.getDependencyDeclarationsWithContext().forEach {
      if (it.statement.leafRule() !is PostfixUnaryExpressionContext) return@forEach

      val context = it.statement.leafRule() as PostfixUnaryExpressionContext
      val declaration = it.declaration

      findAdvice(declaration)?.let { a ->
        if (a.isAnyRemove()) {
          rewriter.delete(context.start, context.stop)
          rewriter.deleteWhitespaceToLeft(context.start)
          rewriter.deleteNewlineToRight(context.stop)
        } else if (a.isAnyChange()) {
          val configuration = context.primaryExpression()
          rewriter.replace(configuration.start, configuration.stop, a.toConfiguration)
        }
      }
    }
  }

  private fun findAdvice(dependencyDeclaration: DependencyDeclaration): Advice? {
    val dependency = reversedDependencyMap(dependencyDeclaration.identifier.path.removeSurrounding("\""))

    return advice.find {
      (it.coordinates.gav() == dependency || it.coordinates.identifier == dependency)
        && it.fromConfiguration == dependencyDeclaration.configuration
    }
  }

  companion object {
    @JvmStatic
    fun of(
      file: Path,
      advice: Set<Advice>,
      advicePrinter: AdvicePrinter,
      reversedDependencyMap: (String) -> String = { it }
    ): KotlinBuildScriptDependenciesRewriter {
      val errorListener = CollectingErrorListener()

      return Parser(
        file = Parser.readOnlyInputStream(file),
        errorListener = errorListener,
        startRule = { it.script() },
        listenerFactory = { input, tokens, _ ->
          KotlinBuildScriptDependenciesRewriter(
            input = input,
            tokens = tokens,
            errorListener = errorListener,
            advice = advice,
            printer = advicePrinter,
            reversedDependencyMap = reversedDependencyMap
          )
        }
      ).listener()
    }
  }
}
