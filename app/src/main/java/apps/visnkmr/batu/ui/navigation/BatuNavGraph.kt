package apps.visnkmr.batu.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import apps.visnkmr.batu.ui.llmchat.BatuLlmChatScreen
import apps.visnkmr.batu.ui.home.BatuHomeScreen

object BatuHomeDestination {
  const val route = "BatuHomeRoute"
}
object BatuLlmChatDestination {
  const val route = "BatuLlmChatRoute"
}

/**
 * Minimal NavHost to mirror Gallery's navigation for the LLM Chat screen.
 */
@Composable
fun BatuNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier
) {
  // Transitions similar to Gallery's slide animations.
  fun enterTween(): FiniteAnimationSpec<IntOffset> = tween(
    durationMillis = 500,
    easing = EaseOutExpo,
    delayMillis = 100
  )
  fun exitTween(): FiniteAnimationSpec<IntOffset> = tween(
    durationMillis = 500,
    easing = EaseOutExpo
  )
  fun AnimatedContentTransitionScope<*>.slideEnter(): EnterTransition =
    slideIntoContainer(
      animationSpec = enterTween(),
      towards = AnimatedContentTransitionScope.SlideDirection.Left
    )
  fun AnimatedContentTransitionScope<*>.slideExit(): ExitTransition =
    slideOutOfContainer(
      animationSpec = exitTween(),
      towards = AnimatedContentTransitionScope.SlideDirection.Right
    )

  NavHost(
    navController = navController,
    startDestination = BatuHomeDestination.route,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
    modifier = modifier
  ) {
    composable(
      route = BatuHomeDestination.route,
      enterTransition = { EnterTransition.None },
      exitTransition = { ExitTransition.None }
    ) {
      BatuHomeScreen(
        onStartChatClicked = { navController.navigate(BatuLlmChatDestination.route) }
      )
    }
    composable(
      route = BatuLlmChatDestination.route,
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() }
    ) {
      BatuLlmChatScreen(
        navigateUp = { navController.navigateUp() }
      )
    }
  }
}
