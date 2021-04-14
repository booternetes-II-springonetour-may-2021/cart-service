package booternetes;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

@SpringBootApplication
public class CartApplication {

	public static void main(String[] args) {
		SpringApplication.run(CartApplication.class, args);
	}

}

@Data
@AllArgsConstructor
@NoArgsConstructor
class Order {
	private Coffee coffee;
	private String username;
	private int quantity;
}

@RestController
@RequiredArgsConstructor
@RequestMapping("/cart/")
class CartRestController
	implements ApplicationListener<RefreshScopeRefreshedEvent> {

	private final Set<Coffee> coffees = new ConcurrentSkipListSet<>();

	private final Environment environment;

	private final Object monitor = new Object();

	@GetMapping("/coffees")
	Flux<Coffee> get() {
		synchronized (this.monitor) {
			return Flux.fromIterable(this.coffees);
		}
	}

	///todo
	@PostMapping("/orders")
	Mono<Void> placeOrder(@RequestBody Mono<Order> order) {
		return Mono.empty();
	}

	@Override
	public void onApplicationEvent(RefreshScopeRefreshedEvent refreshed) {
		var coffees = this.environment.getProperty("coffees");
		Assert.hasText(coffees, () -> "the coffees configuration string must be non-empty");
		var update = Arrays.stream(coffees.split(";")).map(Coffee::new).collect(Collectors.toSet());
		synchronized (this.monitor) {
			this.coffees.clear();
			this.coffees.addAll(update);
		}
	}
}

@Data
@RequiredArgsConstructor
class Coffee implements Comparable<Coffee> {

	private final String name;

	@Override
	public int compareTo(Coffee o) {
		return this.name.compareTo(o.name);
	}
}