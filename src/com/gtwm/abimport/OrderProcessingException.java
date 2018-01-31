package com.gtwm.abimport;

import java.util.List;

public class OrderProcessingException extends Exception {
	
	private OrderProcessingException() {}
	
	public OrderProcessingException(String message, int orderNumber) {
        super(message + "\n\nOrder number: " + orderNumber);
    }

	public OrderProcessingException(String message, int orderNumber, List<String> orderLine, String messageSource) {
        super(message + "\n\nOrder number: " + orderNumber + "\n\nOrder line: " + orderLine + "\n\n\n---\n" + messageSource);
    }

}
