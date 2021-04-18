package info7255.dao;

import org.springframework.stereotype.Repository;
import redis.clients.jedis.Jedis;


@Repository
public class MessageQueueDao {

	// Add value to message queue
	public void addToQueue(String queue, String value) {
		try (Jedis jedis = new Jedis("localhost")) {
			jedis.lpush(queue, value);
		}
	}
}
