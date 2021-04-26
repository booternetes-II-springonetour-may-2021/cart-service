package booternetes;

import io.r2dbc.spi.ConnectionFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Objects;

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


/**
	* How are you? i want to demonstrate reconnecting to a R2DBC ConnectionFactory if the DB goes down (as it might in a Kubernetes cluster)
	* What's the best approach for this? i gather r2dbc-pool might support something like this? is there an AOP retrying ConnectionFactory BeanPostProcessor to write? im not sure what id do here (edited)
	* Hi. I’m good my friend. Using R2DBC Pool is the way to go. If you see anything hanging let me know so we can fix things in the pool or drivers. Make sure to tune timeouts (connection creation acquisition timeouts) as you’d wait otherwise forever.
	*/
@Component
@RequiredArgsConstructor
class CafeInitializer {

	private final Environment env;
	private final CoffeeRepository repo;

	@EventListener({ApplicationReadyEvent.class, RefreshScopeRefreshedEvent.class})
	public	void refill() {
		var coffees = env.getProperty("coffees");
		var deleteAll = repo.deleteAll();
		var writes = repo.saveAll(Flux.fromStream(Arrays.stream(Objects.requireNonNull(coffees).split(";")).map(name -> new Coffee(null, name.trim()))));
		deleteAll.thenMany(writes).subscribe(cafe -> System.out.println("adding " + cafe + '.'));
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
@RequiredArgsConstructor
class OrderRestController {

	private final OrderRepository orderRepository;
	private final WebClient http;

	@PostMapping("/cart/orders")
	Mono<Order> placeOrder(@RequestBody Order order) {
		return this.orderRepository.save(order).doOnNext(this::emit);
	}

	private void emit(Order order) {
		this.http
			.post()
			.uri("http://localhost:8081/dataflow")
			.body(Mono.just (order )  , Order.class )
			.retrieve()
			.bodyToMono(String.class)
			.subscribe(json ->
				System.out.println("posted the order # " + json)
			);
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
