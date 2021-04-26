package booternetes;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.annotation.Id;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
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

	@Bean
	WebClient http(WebClient.Builder builder) {
		return builder.build();
	}
}


/// orders
interface OrderRepository extends ReactiveCrudRepository<Order, Integer> {
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class Order {

	@Id
	private Integer id;
	private Coffee coffee;
	private String username;
	private int quantity;
}


class OrderPlacedEvent extends ApplicationEvent {

	@Override
	public Order getSource() {
		return (Order) super.getSource();
	}

	OrderPlacedEvent(Order source) {
		super(source);
	}
}

@Component
@RequiredArgsConstructor
class PointsSynchronizer implements ApplicationListener<OrderPlacedEvent> {

	private final WebClient http;

	@Override
	public void onApplicationEvent(OrderPlacedEvent orderMadeEvent) {
		this.http
			.post()
			.uri("http://localhost:8081/orders")
			.body(orderMadeEvent.getSource(), Order.class)
			.retrieve()
			.bodyToMono(String.class)
			.subscribe(order ->
				System.out.println("posted the order # " + order)
			);
	}
}

@RestController
@RequiredArgsConstructor
class OrderRestController {

	private final OrderRepository orderRepository;

	private final ApplicationEventPublisher publisher;

	@PostMapping("/cart/orders")
	Mono<Order> placeOrder(@RequestBody Order order) {
		return this.orderRepository.save(order)
			.doOnNext(o -> this.publisher.publishEvent(new OrderPlacedEvent(o)));
	}
}

/// coffee
@Data
@RequiredArgsConstructor
class Coffee implements Comparable<Coffee> {

	private final String name;

	@Override
	public int compareTo(Coffee o) {
		return this.name.compareTo(o.name);
	}
}

@RestController
@RequiredArgsConstructor
class CoffeeRestController implements ApplicationListener<RefreshScopeRefreshedEvent> {

	private final Environment environment;
	private final Set<Coffee> coffees = new ConcurrentSkipListSet<>();
	private final Object monitor = new Object();

	@GetMapping("/cart/coffees")
	Flux<Coffee> get() {
		synchronized (this.monitor) {
			return Flux.fromIterable(this.coffees);
		}
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
