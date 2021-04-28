package booternetes;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.r2dbc.spi.ConnectionFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.r2dbc.connection.init.CompositeDatabasePopulator;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

@SpringBootApplication
public class CartApplication {

	public static void main(String[] args) {
		System.out.println("CWD: " + new File(".").getAbsolutePath());
		System.getenv().forEach((key, value) -> System.out.println(key + '=' + value));
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

@Log4j2
@Component
@RequiredArgsConstructor
class CafeInitializer {

	private final Environment env;
	private final CoffeeRepository repo;

	@EventListener({
		ApplicationReadyEvent.class,
		RefreshScopeRefreshedEvent.class
	})
	public void refill() {
		var coffees = env.getProperty("cart.coffees", "");
		log.info("cart.coffees=" + coffees);
		var deleteAll = repo.deleteAll();
		var coffeesStream = Arrays
			.stream(coffees.split(";"))
			.filter(c -> c != null && !c.trim().equalsIgnoreCase(""))
			.map(name -> new Coffee(null, name.trim()));
		var coffeeObjects = Flux.fromStream(coffeesStream);
		var writes = repo.saveAll(coffeeObjects);
		deleteAll
			.thenMany(writes)
			.subscribe(cafe -> log.info(" ...adding " + cafe + '.'));
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

	private final CircuitBreaker circuitBreaker = CircuitBreaker.of("dataflow-cb", CircuitBreakerConfig
		.custom()//
		.failureRateThreshold(50)//
		.recordExceptions(WebClientResponseException.InternalServerError.class)//
		.slidingWindowSize(5)//
		.waitDurationInOpenState(Duration.ofMillis(1000))//
		.permittedNumberOfCallsInHalfOpenState(2) //
		.build()
	);
	private final OrderRepository orderRepository;
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
			.retryWhen(Retry.backoff(10, Duration.ofSeconds(1)))
			.transformDeferred(CircuitBreakerOperator.of(this.circuitBreaker));
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
