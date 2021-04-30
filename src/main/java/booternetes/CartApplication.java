package booternetes;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.r2dbc.spi.ConnectionFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.r2dbc.connection.init.CompositeDatabasePopulator;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@SpringBootApplication
public class CartApplication {

	public static void main(String[] args) {
		SpringApplication.run(CartApplication.class, args);
	}

	@Bean
	ConnectionFactoryInitializer databaseInitializer(ConnectionFactory cf) {

		var populator = new CompositeDatabasePopulator(
			new ResourceDatabasePopulator(new ClassPathResource("schema.sql")),
			new ResourceDatabasePopulator(new ClassPathResource("data.sql"))
		);

		var initializer = new ConnectionFactoryInitializer();
		initializer.setConnectionFactory(cf);
		initializer.setDatabasePopulator(populator);
		return initializer;
	}

	@Bean
	WebClient webClient(WebClient.Builder builder) {
		return builder.build();
	}

}

interface CoffeeRepository extends ReactiveCrudRepository<Coffee, Integer> {
}

interface OrderRepository extends ReactiveCrudRepository<Order, Integer> {
}

@Data
@Table("cafe")
@AllArgsConstructor
@NoArgsConstructor
class Coffee {

	@Id
	private Integer id;
	private String name;
}


@Data
@Table("cafe_orders")
@AllArgsConstructor
@NoArgsConstructor
class Order {

	@Id
	private Integer id;
	private String coffee;
	private String username;
	private int quantity;
}

@RestController
class OrderRestController {

	private final String cartPointsSinkUrl;
	private final OrderRepository orderRepository;
	private final RateLimiter rateLimiter = RateLimiter.of("dataflow-rl",
		RateLimiterConfig
			.custom()
			.limitForPeriod(10)
			.limitRefreshPeriod(Duration.ofSeconds(1))
			.timeoutDuration(Duration.ofMillis(25))
			.build()
	);
	private final WebClient http;

	OrderRestController(@Value("${cart.points-sink-url}") String cartPointsSinkUrl,
																					OrderRepository orderRepository,
																					WebClient http) {
		this.cartPointsSinkUrl = cartPointsSinkUrl;
		this.orderRepository = orderRepository;
		this.http = http;
	}

	@PostMapping("/cart/orders")
	Mono<Void> placeOrder(@RequestBody Order order) {
		return this.orderRepository
			.save(order)
			.flatMap(this::send)
			.doOnNext(System.out::println)
			.then();
	}

	private Mono<String> send(Order order) {
		var payload =
			Map.of("username", order.getUsername(), "amount", order.getQuantity());
		return this.http
			.post()
			.uri(this.cartPointsSinkUrl)
			.body(Mono.just(payload), Map.class)
			.retrieve()
			.bodyToMono(String.class)
			.doFinally(signal -> System.out.println("signal :" + signal.toString()))
			.doOnError(ex -> System.out.println("OOPS! " + ex.toString()))
			.onErrorResume(ex -> Mono.empty())
			// .retryWhen(Retry.backoff(5, Duration.ofSeconds(1)))
			// .timeout(Duration.ofSeconds(10))
			.transformDeferred(RateLimiterOperator.of(this.rateLimiter));
	}
}

@RestController
@RequiredArgsConstructor
class CoffeeRestController {

	private final CoffeeRepository cafe;

	@GetMapping("/cart/coffees")
	Flux<Coffee> get() {
		return cafe.findAll();
	}
}
