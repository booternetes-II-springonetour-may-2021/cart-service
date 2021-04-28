@RestController
class StubDataflowController {

    @PostMapping ("/")
    def accept (@RequestBody Object payload) {
        println(payload)
    }
}