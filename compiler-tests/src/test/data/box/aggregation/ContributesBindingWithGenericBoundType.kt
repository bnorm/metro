import kotlin.test.*

interface ContributedInterface<T>

@ContributesBinding(
  AppScope::class,
  binding<ContributedInterface<String>>()
)
@Inject
class Impl : ContributedInterface<String>

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val contributedInterface: ContributedInterface<String>
}

fun box(): String {
  val contributedInterface = createGraph<ExampleGraph>().contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(contributedInterface::class.simpleName, "Impl")
  return "OK"
}
