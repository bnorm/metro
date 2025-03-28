import kotlin.test.*

interface ContributedInterface

@Named("named")
@ContributesBinding(AppScope::class)
@Inject
class Impl : ContributedInterface

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Named("named") val contributedInterface: ContributedInterface
}

fun box(): String {
  val contributedInterface = createGraph<ExampleGraph>().contributedInterface
  assertNotNull(contributedInterface)
  assertEquals(contributedInterface::class.simpleName, "Impl")
  return "OK"
}
