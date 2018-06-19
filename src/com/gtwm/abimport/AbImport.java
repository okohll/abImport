package com.gtwm.abimport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
// import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.DataFormatException;
import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import org.postgresql.jdbc3.Jdbc3SimpleDataSource;

public class AbImport {
	public AbImport() {
  }

	public static void main(String[] args) throws Exception {
		try {
			int orderNumber = processOrder();
			// emailSuccess(orderNumber);
		} catch (OrderProcessingException opex) {
			emailError(opex);
		} catch (IOException ioex) {
			emailError(ioex);
		} catch (DataFormatException dfex) {
			emailError(dfex);
		} catch (SQLException sqlex) {
			sqlex.printStackTrace();
			emailError(sqlex);
		} catch (ClassNotFoundException cnfex) {
			emailError(cnfex);
		} catch (ParseException pex) {
			emailError(pex);
		}
	}

	private static void emailError(Exception ex) throws MessagingException {
		String subject = "FF Order processing error";
		String bodyText = "Error importing FarmFresh order\n\n" + ex.getMessage()
				+ "\n\nTechnical details:\n" + ex;
		sendEmail(subject, toAddressString, ccAddressString, bodyText);
		System.out.println(ex.toString());
	}

	private static void emailSuccess(int orderNumber) throws MessagingException {
		String subject = "FF Order " + orderNumber + " processed";
		String bodyText = "Order " + orderNumber + " processed successfully\n";
		sendEmail(subject, toAddressString, ccAddressString, bodyText);
	}

	private static void sendEmail(String subject, String toAddressString, String ccAddressString,
			String bodyText) throws MessagingException, AddressException {
		Properties props = new Properties();
		props.setProperty("mail.smtp.host", "localhost");
		Session mailSession = Session.getDefaultInstance(props, null);
		MimeMessage message = new MimeMessage(mailSession);
		message.setSubject(subject);
		Address toAddress = new InternetAddress(toAddressString);
		message.addRecipient(Message.RecipientType.TO, toAddress);
		if (ccAddressString != null) {
			if (!ccAddressString.equals("")) {
				Address ccAddress = new InternetAddress(ccAddressString);
				message.addRecipient(Message.RecipientType.CC, ccAddress);
			}
		}
		Address fromAddress = new InternetAddress("import@agilebase.co.uk");
		message.setFrom(fromAddress);
		// Message text
		Multipart multipart = new MimeMultipart();
		BodyPart textBodyPart = new MimeBodyPart();
		textBodyPart.setText(bodyText);
		multipart.addBodyPart(textBodyPart);
		message.setContent(multipart);
		Transport.send(message);
	}

	private static void saveErrorLine(Connection conn, int orderKey, Integer orderNumber,
			Integer stockCode, Double quantity, String product, String weight, Double unitPrice,
			String errorMessage) throws SQLException, AddressException, MessagingException {
		String SQLCode = "INSERT INTO " + errorsTable + "(" + errorsOrderFkey + ", "
				+ errorStockCodeField + ", " + errorQuantityField + ", " + errorProductField + ", "
				+ errorWeightField + ", " + errorUnitPriceField + ", " + errorMessageField
				+ ") VALUES(?,?,?,?,?,?,?)";
		PreparedStatement insertStatement = conn.prepareStatement(SQLCode);
		insertStatement.setInt(1, orderKey);
		insertStatement.setInt(2, stockCode);
		insertStatement.setDouble(3, quantity);
		insertStatement.setString(4, product);
		insertStatement.setString(5, weight);
		insertStatement.setDouble(6, unitPrice);
		insertStatement.setString(7, errorMessage);
		int rowsInserted = insertStatement.executeUpdate();
		if (rowsInserted != 1) {
			String bodyText = "Error saving line in order " + orderNumber + ":\n\n" + "Stock code: "
					+ stockCode + ", quantity: " + quantity + ", product: " + product + ", weight: " + weight
					+ ", unit price: " + unitPrice + ", error message: " + errorMessage;
			sendEmail("Import error inserting error", toAddressString, ccAddressString, bodyText);
		} else {
			String bodyText = "Error logged for line in order " + orderNumber + "\n\n";
			bodyText += "Stock code: " + stockCode + ", quantity: " + quantity + ", product: " + product
					+ ", weight: " + weight + ", unit price: " + unitPrice + ", error message: "
					+ errorMessage + "\n\n";
			bodyText += "The remainder of the order has still been imported";
			sendEmail("Import error logged", toAddressString, ccAddressString, bodyText);
		}
		insertStatement.close();
	}

	/**
	 * @return The order number of the order processed
	 */
	private static int processOrder() throws IOException, DataFormatException, SQLException,
			ClassNotFoundException, OrderProcessingException, ParseException, AddressException,
			MessagingException {
		Connection testConn = getConnection();
		testConn.close();
		// Test connection
		try {
			Statement statement = testConn.createStatement();
			ResultSet results = statement.executeQuery("SELECT 1");
			results.close();
			statement.close();
		} finally {
			if (testConn != null) {
				testConn.close();
			}
		}
		// Read piped input
		BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
		String line = null;
		Map<String, String> orderHeader = new HashMap<String, String>();
		List<List<String>> orderLines = new LinkedList<List<String>>();
		boolean processingLines = false;
		StringBuilder source = new StringBuilder();
		// Read the standard input into a data structure representing the order
		EMAIL_LINES: while ((line = input.readLine()) != null) {
			if (line.endsWith("=")) {
				String restOfLine = input.readLine();
				if (restOfLine.startsWith("> ")) {
					restOfLine = restOfLine.substring(2);
				}
				if (restOfLine != null) {
					line = line.replaceAll("=$", "") + restOfLine;
				}
			}
			line = line.replace("=20", " ");
			source.append(line).append("\n");
			// If message has been forwarded, strip >
			if (line.startsWith("> ")) {
				line = line.substring(2);
			}
			if (processingLines) {
				String[] lineArray = line.split("~");
				List<String> lineList = new LinkedList<String>();
				for (String lineItem : lineArray) {
					String trimmedLineItem = lineItem.trim();
					lineList.add(trimmedLineItem);
					if (trimmedLineItem.equals("END_ORDER_DETAIL")) {
						processingLines = false;
					}
				}
				if (processingLines) {
					orderLines.add(lineList);
				}
			} else {
				String[] keyValueArray = line.split("~");
				String key = keyValueArray[0].trim();
				String value = "";
				if (keyValueArray.length > 1) {
					value = keyValueArray[1].trim();
				}
				orderHeader.put(key, value);
				if (key.equals("START_ORDER_DETAIL")) {
					processingLines = true;
				} else if (key.equals("END_TRACT_EDI_ORDERS_FILE")) {
					break EMAIL_LINES;
				}
			}
		}
		// Some sanity checks
		if (!orderHeader.containsKey("START_TRACT_EDI_ORDERS_FILE")) {
			throw new DataFormatException("START_TRACT_EDI_ORDERS_FILE not found in the email.");
		}
		if (!orderHeader.containsKey("START_ORDER_DETAIL")) {
			throw new DataFormatException("START_ORDER_DETAIL not found in the email.");
		}
		if (orderLines.size() == 0) {
			throw new DataFormatException("No order details found in the email");
		}
		Connection conn = getConnection();
		try {
			String orderNumberString = orderHeader.get("ORDERNO");
			int orderNumber = Integer.valueOf(orderNumberString);
			// look up supplier
			String supplierCode = orderHeader.get("SUPCODE");
			String getSupplierSQLCode = "SELECT " + supplierPkey + " FROM " + supplierTable + " WHERE "
					+ supplierCodeField + " = ?";
			PreparedStatement getSupplierStatement = conn.prepareStatement(getSupplierSQLCode);
			getSupplierStatement.setString(1, supplierCode);
			ResultSet supplierResults = getSupplierStatement.executeQuery();
			int supplierId = -1;
			if (supplierResults.next()) {
				supplierId = supplierResults.getInt(1);
			} else {
				throw new SQLException("For order " + orderNumber
						+ ", unable to find supplier with LadyLodge code " + supplierCode);
			}
			supplierResults.close();
			getSupplierStatement.close();
			// insert order header
			String insertSQLCode = "INSERT INTO " + orderHeaderTable + "(" + orderNumField + ", "
					+ orderDateField + ", " + orderDeliveryDateField + ", " + orderCustomerFkey + ", "
					+ orderSupplierFkey + ", " + orderProcessedField + ") values(?,?,?,?,?,?)";
			PreparedStatement insertHeaderStatement = conn.prepareStatement(insertSQLCode,
					Statement.RETURN_GENERATED_KEYS);
			insertHeaderStatement.setInt(1, orderNumber);
			DateFormat formatter = new SimpleDateFormat("yyyyMMdd");
			String orderDateString = orderHeader.get("ORDERDATE");
			long orderDateEpoch = formatter.parse(orderDateString).getTime();
			Timestamp orderDate = new Timestamp(orderDateEpoch);
			insertHeaderStatement.setTimestamp(2, orderDate);
			String deliveryDateString = orderHeader.get("DELIVDATE");
			long deliveryDateEpoch = formatter.parse(deliveryDateString).getTime();
			Timestamp deliveryDate = new Timestamp(deliveryDateEpoch);
			insertHeaderStatement.setTimestamp(3, deliveryDate);
			insertHeaderStatement.setInt(4, 1); // Always customer number 1
			insertHeaderStatement.setInt(5, supplierId);
			insertHeaderStatement.setBoolean(6, false); // Order processed always
																									// false on creation
			int rowsInserted = insertHeaderStatement.executeUpdate();
			if (rowsInserted != 1) {
				throw new SQLException("" + rowsInserted + " order rows inserted instead of 1");
			}
			ResultSet keysResult = insertHeaderStatement.getGeneratedKeys();
			int orderKey = -1;
			if (keysResult.next()) {
				orderKey = keysResult.getInt(1);
			} else {
				throw new SQLException("Unable to get primary key for inserted row");
			}
			insertHeaderStatement.close();
			// get product details
			String getProductSQLCode = "SELECT " + productPkeyField + ", " + productPriceField + " FROM "
					+ productsTable + " WHERE " + productStockCodeField + " = ?";
			PreparedStatement getProductStatement = conn.prepareStatement(getProductSQLCode);
			// insert new product
			String insertProductSQLCode = "INSERT INTO " + productsTable + "(" + productStockCodeField
					+ ", " + productNameField + ", " + productPriceField + ", " + productVatCodeField + ", " + productArchivedField + ", " + productLockedField + ", "
					+ productLastModifiedField + ", " + productCreationTimeField + ", "
					+ productCreatedByField + ", " + productModifiedByField + ") VALUES(?, ?, ?, 'T1', false, false, now(), now(), 'Order Import (FF)', 'Order Import (FF)')";
			PreparedStatement insertProductStatement = conn.prepareStatement(insertProductSQLCode);
			// update product price
			String priceChangeSQL = "UPDATE " + productsTable + " SET " + productPriceField
					+ " = ? WHERE " + productPkeyField + " = ?";
			PreparedStatement priceChangeStatement = conn.prepareStatement(priceChangeSQL);
			// insert order line
			String insertLineSQLCode = "INSERT INTO " + orderLinesTable + "(" + orderLinesOrderFkey
					+ ", " + orderLinesProductFkey + ", " + orderLinesQuantityField + ", "
					+ orderLinesUnitPriceField + ") VALUES(?,?,?,?)";
			PreparedStatement insertOrderLineStatement = conn.prepareStatement(insertLineSQLCode);
			for (List<String> orderLine : orderLines) {
				// Sample line
				// 5026~ 4.00~ 5026~S/F AUTH CHICKEN TIKKA ~ 1kg~ 3.79~
				String quantityString = orderLine.get(1);
				double quantity = Double.valueOf(quantityString);
				String codeString = orderLine.get(2);
				int stockCode = Integer.valueOf(codeString);
				String product = orderLine.get(3);
				String priceString = orderLine.get(5);
				double orderPrice = Double.valueOf(priceString);
				// Get product details and check the price against the value
				// given
				getProductStatement.setInt(1, stockCode);
				ResultSet productResults = getProductStatement.executeQuery();
				boolean productFound = productResults.next();
				if (!productFound) {
					// Insert a new product matching the order line, if no matching product found in the table
					insertProductStatement.setInt(1, stockCode);
					insertProductStatement.setString(2, product);
					insertProductStatement.setDouble(3, orderPrice);
					int productRowsInserted = insertProductStatement.executeUpdate();
					if (productRowsInserted != 1) {
						throw new SQLException("Expected to insert one product, instead inserted "
								+ productRowsInserted + ". Order line = " + orderLine);
					}
					String subject = "FF new product notification";
					String body = "Product " + product + " (" + stockCode + ") has been added to the database due to order number " + orderNumber;
					sendEmail(subject, toAddressString, ccAddressString, body);
					// Redo the product query now there's a product there
					productResults.close();
					productResults = getProductStatement.executeQuery();
					productFound = productResults.next();
				}
				if (productFound) {
					int productKey = productResults.getInt(1);
					double productPrice = productResults.getDouble(2);
					if (Math.abs(productPrice - orderPrice) > 0.01) {
						// Update product price from order price and email a price change
						// notification
						String weight = orderLine.get(4);
						priceChangeStatement.setDouble(1, orderPrice);
						priceChangeStatement.setInt(2, productKey);
						int rowsUpdated = priceChangeStatement.executeUpdate();
						if (rowsUpdated != 1) {
							throw new SQLException("Expected to update price for one product, instead updated "
									+ rowsUpdated + ". Order line = " + orderLine);
						}
						String subject = "FF price change notification";
						String body = "Product " + product + " (" + stockCode + ") has changed price from "
								+ productPrice + " to " + orderPrice + " due to order number " + orderNumber;
						sendEmail(subject, toAddressString, ccAddressString, body);
						/*
						 * saveErrorLine(conn, orderKey, orderNumber, stockCode, quantity,
						 * product, weight, orderPrice, "Price in order of " + orderPrice +
						 * " doesn't match product price " + productPrice);
						 */
					}
					insertOrderLineStatement.setInt(1, orderKey);
					insertOrderLineStatement.setInt(2, productKey);
					insertOrderLineStatement.setDouble(3, quantity);
					insertOrderLineStatement.setDouble(4, orderPrice);
					rowsInserted = insertOrderLineStatement.executeUpdate();
					if (rowsInserted != 1) {
						throw new SQLException("Excpected to insert 1 order line, instead inserted "
								+ rowsInserted + ". Order line = " + orderLine);
					}
				} else {
					String weight = orderLine.get(4);
					saveErrorLine(conn, orderKey, orderNumber, stockCode, quantity, product, weight,
							orderPrice, "Product with stock code " + stockCode
									+ " can't be found in the products database");
				}
				productResults.close();
			}
			insertOrderLineStatement.close();
			getProductStatement.close();
			priceChangeStatement.close();
			insertProductStatement.close();
			conn.commit();
			return orderNumber;
		} finally {
			if (conn != null) {
				conn.close();
			}
		}
	}

	private static Connection getConnection() throws SQLException {
		String connectionStatement = "jdbc:postgresql://localhost/agilebasedata?ssl=true";

		Properties connectionProperties = new Properties();
		connectionProperties.setProperty("user", "gtpb");
		//Connection conn = DriverManager.getConnection(connectionStatement, connectionProperties);
		source.setServerName("localhost");
		source.setDatabaseName("agilebasedata");
		source.setSsl(true);
		source.setUser("gtpb");
		Connection conn = source.getConnection();
		conn.setAutoCommit(false);
		return conn;
	}

	// a6) suppliers
	private static final String supplierTable = "a69307b65f830b06d";

	// LadyLodge Code
	private static final String supplierCodeField = "chotc6dgruqlwmfuc";

	private static final String supplierPkey = "a3b8a7eb12dd8d381";

	private static final String orderHeaderTable = "vrrvghc3agkckbiwi";

	private static final String orderNumField = "aslvpadrrygoortys";

	private static final String orderDateField = "xsecfuyw2mwmlk6xf";

	private static final String orderDeliveryDateField = "ouyhe5z3sxfe58pf7";

	private static final String orderSupplierFkey = "ssmyrprm429vjoej7";

	private static final String orderCustomerFkey = "jisosnlfucprvi42u";

	private static final String orderProcessedField = "lzimpr1n1ryyuuc8y";

	private static final String productsTable = "a653134ebba170ce5";

	private static final String productPriceField = "af74bfa478e2718e9";

	private static final String productPkeyField = "a910f19ef69be4906";

	private static final String productStockCodeField = "a60e1878b783527d7";

	private static final String productNameField = "a122d42d203ba4a07";

	private static final String productVatCodeField = "a3873bb2afa75bb3d";
	
	private static final String productArchivedField = "ad1e3354def1a16df";

	private static final String productLockedField = "a3d206bf71ea30fa5";
	
	private static final String productLastModifiedField = "ab184a00916531550";

	private static final String productCreationTimeField = "ad470a7e55cdd228";

	private static final String productCreatedByField = "a9ea861e525b58544";

	private static final String productModifiedByField = "a93e3bafb9fb93583";

	private static final String orderLinesTable = "iyfrzpleqthztg9wp";

	private static final String orderLinesOrderFkey = "cxxvpsv6jpjf1tutf";

	private static final String orderLinesProductFkey = "rjan6lcnzuh5fsdqu";

	private static final String orderLinesQuantityField = "byxbcnkrpsmthvx2d";

	private static final String orderLinesUnitPriceField = "imvf3zb6igy8lr1qk";

	private static final String errorsTable = "ad8exc90n6rcdmb0i";

	private static final String errorsOrderFkey = "soc7sfvbroi2nxb4j";

	private static final String errorStockCodeField = "kmizzcm7ujsh1njcb";

	private static final String errorQuantityField = "cdnui8vke4uhlybug";

	private static final String errorProductField = "qwyrcujeh7twb6ieq";

	private static final String errorWeightField = "ukxjgckpagd0jcohg";

	private static final String errorUnitPriceField = "hfragwxv23rm3omba";

	private static final String errorMessageField = "swucnzw7d299iqjdk";

	private static final String toAddressString = "gareth.curtis@chfoods.co.uk";

	private static final String ccAddressString = "oliver@agilebase.co.uk";
	
	private static final Jdbc3SimpleDataSource source = new Jdbc3SimpleDataSource();

}
