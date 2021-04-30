package booternetes;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
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

import java.time.Duration;
import java.util.Arrays;
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

// MG
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


// JL
interface CoffeeRepository extends ReactiveCrudRepository<Coffee, Integer> {
}

// JL
interface OrderRepository extends ReactiveCrudRepository<Order, Integer> {
}

// JL
@Data
@Table("cafe")
@AllArgsConstructor
@NoArgsConstructor
class Coffee {

	@Id
	private Integer id;
	private String name;
}

// JL
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

// JL
@RestController
class OrderRestController {

	private final String cartPointsSinkUrl;


	private final OrderRepository orderRepository;
	// MH <

	//https://github.com/reactive-spring-book/orchestration/blob/master/client/src/main/java/rsb/orchestration/resilience4j/RateLimiterClient.java
	private final RateLimiter rateLimiter = RateLimiter.of("dataflow-rl",
		RateLimiterConfig//
			.custom() //
			.limitForPeriod(10)// <1>
			.limitRefreshPeriod(Duration.ofSeconds(1))// <2>
			.timeoutDuration(Duration.ofMillis(25))//
			.build()
	);


/*	private final CircuitBreaker circuitBreaker = CircuitBreaker.of("dataflow-cb", CircuitBreakerConfig
		.custom()//
		.failureRateThreshold(50)//
		.recordExceptions(WebClientResponseException.InternalServerError.class)//
		.slidingWindowSize(5)//
		.waitDurationInOpenState(Duration.ofMillis(1000))//
		.permittedNumberOfCallsInHalfOpenState(2) //
		.build()
	);*/
	// MH >

	private final WebClient http;

	OrderRestController(
		@Value("${cart.points-sink-url}") String cartPointsSinkUrl,
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

			// step 0: error handling
			.doFinally(signal -> System.out.println("signal :" + signal.toString()))
			.doOnError(ex -> System.out.println("OOPS! " + ex.toString()))
			.onErrorResume(ex -> Mono.empty())

			// step 1:
			// .retryWhen(Retry.backoff(5, Duration.ofSeconds(1)))

			// step 2:
			// .timeout(Duration.ofSeconds(10))

			// step 3:
			.transformDeferred(RateLimiterOperator.of(this.rateLimiter))
			;
		// > MH
	}
}

// JL
@RestController
@RequiredArgsConstructor
class CoffeeRestController {

	private final CoffeeRepository cafe;

	@GetMapping("/cart/coffees")
	Flux<Coffee> get() {
		return cafe.findAll();
	}

}
