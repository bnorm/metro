import kotlin.test.*

interface ContributedInterface

abstract class LoggedInScope private constructor()

@ContributesBinding(LoggedInScope::class)
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class, additionalScopes = [LoggedInScope::class])
interface ExampleGraph {
  val contributedInterface: ContributedInterface
}

fun box(): String {
  val contributedInterface = createGraph<ExampleGraph>().contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(contributedInterface::class.simpleName, "Impl")
  return "OK"
}
