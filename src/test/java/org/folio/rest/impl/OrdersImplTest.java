package org.folio.rest.impl;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import org.apache.commons.lang3.StringUtils;
import org.folio.rest.RestVerticle;
import org.folio.rest.acq.model.PurchaseOrder;
import org.folio.rest.jaxrs.model.Adjustment;
import org.folio.rest.jaxrs.model.CompositePurchaseOrder;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.Location;
import org.folio.rest.jaxrs.model.PoLine;
import org.folio.rest.tools.utils.NetworkUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.folio.orders.utils.HelperUtils.ADJUSTMENT;
import static org.folio.orders.utils.HelperUtils.ALERTS;
import static org.folio.orders.utils.HelperUtils.CLAIMS;
import static org.folio.orders.utils.HelperUtils.COST;
import static org.folio.orders.utils.HelperUtils.DEFAULT_POLINE_LIMIT;
import static org.folio.orders.utils.HelperUtils.DETAILS;
import static org.folio.orders.utils.HelperUtils.ERESOURCE;
import static org.folio.orders.utils.HelperUtils.FUND_DISTRIBUTION;
import static org.folio.orders.utils.HelperUtils.LOCATION;
import static org.folio.orders.utils.HelperUtils.PHYSICAL;
import static org.folio.orders.utils.HelperUtils.PO_LINES;
import static org.folio.orders.utils.HelperUtils.RENEWAL;
import static org.folio.orders.utils.HelperUtils.SOURCE;
import static org.folio.orders.utils.HelperUtils.VENDOR_DETAIL;
import static org.folio.orders.utils.HelperUtils.getMockData;
import static org.folio.rest.RestVerticle.OKAPI_HEADER_TENANT;
import static org.folio.rest.RestVerticle.OKAPI_HEADER_TOKEN;
import static org.folio.rest.RestVerticle.OKAPI_USERID_HEADER;
import static org.folio.rest.impl.OrdersImpl.OVER_LIMIT_ERROR_MESSAGE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


@RunWith(VertxUnitRunner.class)
public class OrdersImplTest {

  public static final String INCORRECT_LANG_PARAMETER = "'lang' parameter is incorrect. parameter value {english} is not valid: must match \"[a-zA-Z]{2}\"";

  public static final String ID = "id";

  static {
    System.setProperty(LoggerFactory.LOGGER_DELEGATE_FACTORY_CLASS_NAME, "io.vertx.core.logging.Log4j2LogDelegateFactory");
  }

  private static final Logger logger = LoggerFactory.getLogger(OrdersImplTest.class);

  private static final String APPLICATION_JSON = "application/json";
  private static final String TEXT_PLAIN = "text/plain";

  private static final int okapiPort = NetworkUtils.nextFreePort();
  private static final int mockPort = NetworkUtils.nextFreePort();

  private static final String EXIST_CONFIG_TENANT = "test_diku";
  private static final String INVALID_EXIST_CONFIG_TENANT = "invalid_config";
  private static final String EMPTY_CONFIG_TENANT = "config_empty";
  private static final String NON_EXIST_CONFIG_TENANT = "ordersimpltest";


  private static final Header X_OKAPI_URL = new Header("X-Okapi-Url", "http://localhost:" + mockPort);
  private static final Header NON_EXIST_CONFIG_X_OKAPI_TENANT = new Header(OKAPI_HEADER_TENANT, NON_EXIST_CONFIG_TENANT);
  private static final Header EXIST_CONFIG_X_OKAPI_TENANT = new Header(OKAPI_HEADER_TENANT, EXIST_CONFIG_TENANT);
  private static final Header INVALID_CONFIG_X_OKAPI_TENANT = new Header(OKAPI_HEADER_TENANT, INVALID_EXIST_CONFIG_TENANT);
  private static final Header EMPTY_CONFIG_X_OKAPI_TENANT = new Header(OKAPI_HEADER_TENANT, EMPTY_CONFIG_TENANT);
  private static final Header X_OKAPI_USER_ID = new Header(OKAPI_USERID_HEADER, "440c89e3-7f6c-578a-9ea8-310dad23605e");
  private static final Header X_OKAPI_TOKEN = new Header(OKAPI_HEADER_TOKEN, "eyJhbGciOiJIUzI1NiJ9");
  private static final Header TMP_ORDER_HEADER = new Header("X-Okapi_Tmp", "tmp_order");

  private static final String X_ECHO_STATUS = "X-Okapi-Echo-Status";

  private static final String INVALID_LANG = "?lang=english";

  private static final String PO_ID = "e5ae4afd-3fa9-494e-a972-f541df9b877e";
  private static final String ID_BAD_FORMAT = "123-45-678-90-abc";
  private static final String ID_DOES_NOT_EXIST = "d25498e7-3ae6-45fe-9612-ec99e2700d2f";
  private static final String ID_FOR_INTERNAL_SERVER_ERROR = "168f8a86-d26c-406e-813f-c7527f241ac3";
  private static final String PO_ID_FOR_SUCCESS_CASE = "95d29d04-34b1-4fe0-a15e-1cd129143692";
  private static final String PO_LINE_ID_FOR_SUCCESS_CASE = "fca5fa9e-15cb-4a3d-ab09-eeea99b97a47";
  private static final String ANOTHER_PO_LINE_ID_FOR_SUCCESS_CASE = "c0d08448-347b-418a-8c2f-5fb50248d67e";
  private static final String PO_LINE_ID_WITH_SOME_SUB_OBJECTS_ALREADY_REMOVED = "0009662b-8b80-4001-b704-ca10971f175d";
  private static final String PO_LINE_ID_WITH_SUB_OBJECT_OPERATION_500_CODE = "c2755a78-2f8d-47d0-a218-059a9b7391b4";

  // API paths
  private final String rootPath = "/orders";
  private final static String LINES_PATH = "/orders/%s/lines";
  private final static String LINE_BY_ID_PATH = "/orders/%s/lines/%s";

  // Mock data paths
  private static final String BASE_MOCK_DATA_PATH = "mockdata/";
  private static final String MOCK_DATA_PATH = BASE_MOCK_DATA_PATH + "getOrders.json";
  private static final String PO_LINES_MOCK_DATA_PATH = BASE_MOCK_DATA_PATH + "lines/";
  private static final String COMP_PO_LINES_MOCK_DATA_PATH = BASE_MOCK_DATA_PATH + "compositeLines/";
  private final String mockDataRootPath = "src/test/resources/";
  private final String listedPrintMonographPath = mockDataRootPath + "/po_listed_print_monograph.json";
  private final String minimalOrderPath = mockDataRootPath + "/minimal_order.json";
  private final String existedOrder = mockDataRootPath + "/existed_order.json";
  private final String poCreationFailurePath = mockDataRootPath + "/po_creation_failure.json";
  private final String poLineCreationFailurePath = mockDataRootPath + "/po_line_creation_failure.json";
  private static final String CONFIG_MOCK_PATH = BASE_MOCK_DATA_PATH + "configurations.entries/%s.json";
  private static final String EMPTY_PO_LINE_BUT_WITH_IDS = "{\"id\": \"%s\", \"purchase_order_id\": \"%s\"}";

  private static final String INTERNAL_SERVER_ERROR = "Internal Server Error";

  private static Vertx vertx;
  private static MockServer mockServer;
  private static JsonObject tmpOrder;

  @BeforeClass
  public static void setUpOnce(TestContext context) {
    vertx = Vertx.vertx();

    mockServer = new MockServer(mockPort);
    mockServer.start(context);

    RestAssured.baseURI = "http://localhost:" + okapiPort;
    RestAssured.port = okapiPort;
    RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

    final JsonObject conf = new JsonObject();
    conf.put("http.port", okapiPort);

    final DeploymentOptions opt = new DeploymentOptions().setConfig(conf);
    vertx.deployVerticle(RestVerticle.class.getName(), opt, context.asyncAssertSuccess());
  }

  @AfterClass
  public static void tearDownOnce(TestContext context) {
    vertx.close(context.asyncAssertSuccess());
    mockServer.close();
  }

  @Test
  public void testListedPrintMonograph() throws Exception {
    logger.info("=== Test Listed Print Monograph ===");

    String body = getMockData(listedPrintMonographPath);
    JsonObject reqData = new JsonObject(body);

    final CompositePurchaseOrder resp = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .header(X_OKAPI_USER_ID)
        .header(X_OKAPI_TOKEN)
        .contentType(APPLICATION_JSON)
        .body(body)
      .post(rootPath)
        .then()
          .contentType(APPLICATION_JSON)
          .statusCode(201)
          .extract()
            .response()
              .as(CompositePurchaseOrder.class);

    logger.info(JsonObject.mapFrom(resp));

    String poId = resp.getId();
    String poNumber = resp.getPoNumber();

    assertNotNull(poId);
    assertNotNull(poNumber);
    assertEquals(reqData.getJsonArray("po_lines").size(), resp.getPoLines().size());

    for (int i = 0; i < resp.getPoLines().size(); i++) {
      PoLine line = resp.getPoLines().get(i);
      String polNumber = line.getPoLineNumber();
      String polId = line.getId();

      assertEquals(poId, line.getPurchaseOrderId());
      assertNotNull(polId);
      assertNotNull(polNumber);
      assertTrue(polNumber.startsWith(poNumber));
      assertNotNull(line.getCost().getId());
      assertNotNull(line.getDetails().getId());
      assertNotNull(line.getLocation().getId());
    }
  }

  @Test
  public void testPlaceOrderMinimal() throws Exception {
    logger.info("=== Test Placement of minimal order ===");

    String body = getMockData(minimalOrderPath);
    JsonObject reqData = new JsonObject(body);

    final CompositePurchaseOrder resp = verifyPostResponse(rootPath, body,
      NON_EXIST_CONFIG_X_OKAPI_TENANT, APPLICATION_JSON, 201).as(CompositePurchaseOrder.class);


    logger.info(JsonObject.mapFrom(resp));

    String poId = resp.getId();
    String poNumber = resp.getPoNumber();

    assertNotNull(poId);
    assertNotNull(poNumber);
    assertEquals(reqData.getJsonArray("po_lines").size(), resp.getPoLines().size());

    for (int i = 0; i < resp.getPoLines().size(); i++) {
      PoLine line = resp.getPoLines().get(i);
      String polNumber = line.getPoLineNumber();
      String polId = line.getId();

      assertEquals(poId, line.getPurchaseOrderId());
      assertNotNull(polId);
      assertNotNull(polNumber);
      assertTrue(polNumber.startsWith(poNumber));
    }
  }

  @Test
  public void testPoCreationFailure(TestContext ctx) throws Exception {
    logger.info("=== Test PO creation failure ===");

    String body = getMockData(poCreationFailurePath);

    final Errors errors = verifyPostResponse(rootPath, body,
      NON_EXIST_CONFIG_X_OKAPI_TENANT, APPLICATION_JSON, 422).body().as(Errors.class);

    logger.info(JsonObject.mapFrom(errors).encodePrettily());

    ctx.assertFalse(errors.getErrors().isEmpty());
    ctx.assertNotNull(errors.getErrors().get(0));
    ctx.assertEquals("must match \"^[a-zA-Z0-9]{5,16}$\"", errors.getErrors().get(0).getMessage());
    ctx.assertFalse(errors.getErrors().get(0).getParameters().isEmpty());
    ctx.assertNotNull(errors.getErrors().get(0).getParameters().get(0));
    ctx.assertEquals("poNumber", errors.getErrors().get(0).getParameters().get(0).getKey());
    ctx.assertEquals("123", errors.getErrors().get(0).getParameters().get(0).getValue());
  }

  @Test
  public void testPoCreationWithOverLimitPOLines(TestContext ctx) throws Exception {
    logger.info("=== Test PO, with over limit lines quantity, creation ===");

    String body = getMockData(listedPrintMonographPath);

    final Errors errors = verifyPostResponse(rootPath, body,
      EXIST_CONFIG_X_OKAPI_TENANT, APPLICATION_JSON, 422).body().as(Errors.class);


    logger.info(JsonObject.mapFrom(errors).encodePrettily());
    ctx.assertFalse(errors.getErrors().isEmpty());
    ctx.assertEquals(String.format(OVER_LIMIT_ERROR_MESSAGE, 1), errors.getErrors().get(0).getMessage());
  }


  @Test
  public void testPostWithInvalidConfig(TestContext ctx) throws Exception {
    logger.info("=== Test PO creation fail if config is invalid ===");

    String body = getMockData(listedPrintMonographPath);

    final String error = verifyPostResponse(rootPath, body,
      INVALID_CONFIG_X_OKAPI_TENANT, TEXT_PLAIN, 500).body().print();

    ctx.assertEquals(error, "Invalid limit value in configuration.");
  }

  @Test
  public void testPostWithEmptyConfigOverLimit(TestContext ctx) {
    logger.info("=== Test PO creation fail with default limit ===");

    JsonObject compPoLineJson = getMockAsJson(COMP_PO_LINES_MOCK_DATA_PATH, ANOTHER_PO_LINE_ID_FOR_SUCCESS_CASE);
    String id = compPoLineJson.getString("purchase_order_id");

    final Errors errors = verifyPostResponse(String.format(LINES_PATH, id), compPoLineJson.encodePrettily(),
      EMPTY_CONFIG_X_OKAPI_TENANT, APPLICATION_JSON, 422).body().as(Errors.class);


    logger.info(JsonObject.mapFrom(errors).encodePrettily());
    ctx.assertFalse(errors.getErrors().isEmpty());
    ctx.assertEquals(String.format(OVER_LIMIT_ERROR_MESSAGE, DEFAULT_POLINE_LIMIT), errors.getErrors().get(0).getMessage());
  }


  @Test
  public void testPoLineCreationIfPoAlreadyReachedLimit(TestContext ctx) {
    logger.info("=== Test PO Line over limit creation ===");
    JsonObject compPoLineJson = getMockAsJson(COMP_PO_LINES_MOCK_DATA_PATH, ANOTHER_PO_LINE_ID_FOR_SUCCESS_CASE);
    String id = compPoLineJson.getString("purchase_order_id");

    final Errors errors = verifyPostResponse(String.format(LINES_PATH, id), compPoLineJson.encodePrettily(),
      EXIST_CONFIG_X_OKAPI_TENANT, APPLICATION_JSON, 422).body().as(Errors.class);


    logger.info(JsonObject.mapFrom(errors).encodePrettily());
    ctx.assertFalse(errors.getErrors().isEmpty());
    ctx.assertEquals(String.format(OVER_LIMIT_ERROR_MESSAGE, 1), errors.getErrors().get(0).getMessage());
  }


  @Test
  public void testPoLineCreationFailure(TestContext ctx) throws Exception {
    logger.info("=== Test POLine creation failure ===");

    String body = getMockData(poLineCreationFailurePath);

    final Errors errors = verifyPostResponse(rootPath, body,
      NON_EXIST_CONFIG_X_OKAPI_TENANT, APPLICATION_JSON, 422).body().as(Errors.class);


    logger.info(JsonObject.mapFrom(errors).encodePrettily());

    ctx.assertFalse(errors.getErrors().isEmpty());
    ctx.assertNotNull(errors.getErrors().get(0));
    ctx.assertEquals("must match \"^[a-zA-Z0-9]{5,16}-[0-9]{1,3}$\"", errors.getErrors().get(0).getMessage());
    ctx.assertFalse(errors.getErrors().get(0).getParameters().isEmpty());
    ctx.assertNotNull(errors.getErrors().get(0).getParameters().get(0));
    ctx.assertEquals("poLines[1].poLineNumber", errors.getErrors().get(0).getParameters().get(0).getKey());
    ctx.assertEquals("invalid", errors.getErrors().get(0).getParameters().get(0).getValue());
  }

  @Test
  public void testDetailsCreationFailure() throws Exception {
    logger.info("=== Test Details creation failure ===");

    String body = getMockData(listedPrintMonographPath);

    final Response resp = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .header(X_OKAPI_USER_ID)
        .header(X_OKAPI_TOKEN)
        .header(X_ECHO_STATUS, 403)
        .contentType(APPLICATION_JSON)
        .body(body)
      .post(rootPath)
        .then()
          .contentType(TEXT_PLAIN)
          .statusCode(500)
            .extract()
              .response();

    String respBody = resp.getBody().asString();
    logger.info(respBody);

    assertEquals("Access requires permission: foo.bar.baz", respBody);
  }

  @Test
  public void testGetOrderById() throws Exception {
    logger.info("=== Test Get Order By Id ===");

    JsonObject ordersList = new JsonObject(getMockData(MOCK_DATA_PATH));
    String id = ordersList.getJsonArray("composite_purchase_orders").getJsonObject(0).getString(ID);
    logger.info(String.format("using mock datafile: %s%s.json", BASE_MOCK_DATA_PATH, id));

    final CompositePurchaseOrder resp = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .get(rootPath + "/" + id)
        .then()
          .contentType(APPLICATION_JSON)
          .statusCode(200)
          .extract()
            .response()
              .as(CompositePurchaseOrder.class);

    logger.info(JsonObject.mapFrom(resp).encodePrettily());

    assertEquals(id, resp.getId());
  }

  @Test
  public void testGetOrderByIdWithOnePoLine() {
    logger.info("=== Test Get Order By Id - With one PO Line and empty source ===");

    String id = "07f65192-44a4-483d-97aa-b137bbd96390";
    logger.info(String.format("using mock datafile: %s%s.json", BASE_MOCK_DATA_PATH, id));

    final CompositePurchaseOrder resp = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .get(rootPath + "/" + id)
        .then()
          .contentType(APPLICATION_JSON)
          .statusCode(200)
          .extract()
            .response()
              .as(CompositePurchaseOrder.class);

    logger.info(JsonObject.mapFrom(resp).encodePrettily());

    assertEquals(id, resp.getId());
    assertEquals(1, resp.getPoLines().size());
    // The source set in file to ID_DOES_NOT_EXIST constant value
    assertNull(resp.getPoLines().get(0).getSource());
  }

  @Test
  public void testGetOrderByIdIncorrectIdFormat() {
    logger.info("=== Test Get Order By Id - Incorrect Id format ===");

    String id = "non-existent-po-id";

    final Response resp = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .get(rootPath + "/" + id)
        .then()
          // The status code should be 400 once Pattern validation annotation is added to Orders interface methods
          .statusCode(404)
          .extract()
            .response();

    String actual = resp.getBody().asString();
    logger.info(actual);

    assertNotNull(actual);
    assertTrue(actual.contains(id));
  }

  @Test
  public void testGetOrderByIdNotFound() {
    logger.info("=== Test Get Order By Id - Not Found ===");

    String id = ID_DOES_NOT_EXIST;

    final Response resp = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .get(rootPath + "/" + id)
        .then()
          .contentType(TEXT_PLAIN)
          .statusCode(404)
          .extract()
            .response();

    String actual = resp.getBody().asString();
    logger.info(actual);

    assertEquals(id, actual);
  }

  @Test
  public void testDeleteById() throws Exception {
    logger.info("=== Test Delete Order By Id ===");

    JsonObject ordersList = new JsonObject(getMockData(MOCK_DATA_PATH));
    String id = ordersList.getJsonArray("composite_purchase_orders").getJsonObject(0).getString(ID);
    logger.info(String.format("using mock datafile: %s%s.json", BASE_MOCK_DATA_PATH, id));

    RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .delete(rootPath + "/" + id)
        .then()
          .statusCode(204);
  }

  @Test
  public void testDeleteByIdNoOrderFound() {
    logger.info("=== Test Delete Order By Id - Not Found ===");
    verifyDeleteResponse(rootPath + "/" + ID_DOES_NOT_EXIST, TEXT_PLAIN, 404);
  }

  @Test
  public void testDeleteById500Error() {
    logger.info("=== Test Delete Order By Id - Storage Internal Server Error ===");
    verifyDeleteResponse(rootPath + "/" + ID_FOR_INTERNAL_SERVER_ERROR, TEXT_PLAIN, 500);
  }

  @Test
  public void testDeleteByIdWithoutOkapiUrlHeader() {
    logger.info("=== Test Delete Order By Id - 500 due to missing Okapi URL header ===");

   RestAssured
      .with()
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .delete(rootPath + "/" + ID_DOES_NOT_EXIST)
        .then()
          .statusCode(500);
  }

  @Test
  public void testPutOrdersById() throws Exception {
    logger.info("=== Test Put Order By Id ===");

    JsonObject ordersList = new JsonObject(getMockData(MOCK_DATA_PATH));
    String id = ordersList.getJsonArray("composite_purchase_orders").getJsonObject(0).getString(ID);
    logger.info(String.format("using mock datafile: %s%s.json", BASE_MOCK_DATA_PATH, id));
    String body = getMockData(listedPrintMonographPath);
    RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .header(X_OKAPI_USER_ID)
        .contentType(APPLICATION_JSON)
        .body(body)
      .put(rootPath + "/" + id)
        .then()
          .statusCode(204);
  }

  @Test
  public void testPutOrdersByIdDoesNotAffectGeneratedData() throws Exception {
    logger.info("=== Test Put Order By Id doesn't affect generated data ===");

    tmpOrder = new JsonObject(getMockData(existedOrder));
    PurchaseOrder initialOrder = tmpOrder.mapTo(PurchaseOrder.class);
    CompositePurchaseOrder puttedOrder =  tmpOrder.mapTo(CompositePurchaseOrder.class);
    puttedOrder.setApproved(false);
    puttedOrder.setCreated(new Date());
    puttedOrder.setCreatedBy("440c89e3-7f6c-578a-9ea8-310dad23605e");
    String body = JsonObject.mapFrom(puttedOrder).toString();

    RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .header(X_OKAPI_USER_ID)
        .header(TMP_ORDER_HEADER)
        .contentType(APPLICATION_JSON)
      .body(body)
        .put(rootPath + "/1ab7ef6a-d1d4-4a4f-90a2-882aed18af14")
          .then()
            .statusCode(204);

    PurchaseOrder changedOrder =  tmpOrder.mapTo(PurchaseOrder.class);

    assertThat(initialOrder.getCreated(), equalTo(changedOrder.getCreated()));
    assertThat(initialOrder.getCreatedBy(), equalTo(changedOrder.getCreatedBy()));
    assertThat(initialOrder.getApproved(), not(equalTo(changedOrder.getApproved())));
    assertThat(puttedOrder.getApproved(), equalTo(changedOrder.getApproved()));
    assertThat(puttedOrder.getCreatedBy(), not(equalTo(changedOrder.getCreatedBy())));

  }

  @Test
  public void testIgnoringGeneratedDataInResponseOnPost() throws IOException {
    logger.info("=== Test ignoring \"Created on\" from request on POST API ===");

    String body = getMockData(minimalOrderPath);
    JsonObject reqData = new JsonObject(body);
    CompositePurchaseOrder compPo = reqData.mapTo(CompositePurchaseOrder.class);
    Date dateFromRequest = compPo.getCreated();
    String createdByFromRequest = compPo.getCreatedBy();
    String poNumberFromRequest = compPo.getPoNumber();
    final CompositePurchaseOrder resp = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .header(X_OKAPI_USER_ID)
        .header(X_OKAPI_TOKEN)
        .contentType(APPLICATION_JSON)
        .body(body)
      .post(rootPath)
        .then()
          .contentType(APPLICATION_JSON)
          .statusCode(201)
          .extract()
            .response()
              .as(CompositePurchaseOrder.class);

    logger.info(JsonObject.mapFrom(resp));
    Date dateFromResponse = resp.getCreated();
    String createdByFromResponse = resp.getCreatedBy();
    String poNumberFromResponse = resp.getPoNumber();
    assertNotNull(dateFromResponse);
    assertNotNull(createdByFromResponse);
    assertNotNull(poNumberFromResponse);
    assertThat(poNumberFromRequest, not(equalTo(poNumberFromResponse)));
    assertThat(dateFromResponse, not(equalTo(dateFromRequest)));
    assertThat(createdByFromResponse, not(equalTo(createdByFromRequest)));
  }

  @Test
  public void testValidationOnPost() {
    logger.info("=== Test validation Annotation on POST API ===");

    logger.info("=== Test validation with no body ===");
    RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .header(X_OKAPI_USER_ID)
        .contentType(APPLICATION_JSON)
      .post(rootPath)
        .then()
          .statusCode(400)
          .body(containsString("Json content error HV000116: The object to be validated must not be null"));

    logger.info("=== Test validation on invalid lang query parameter ===");
    RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .header(X_OKAPI_USER_ID)
        .header(X_OKAPI_TOKEN)
        .contentType(APPLICATION_JSON)
        .body("{}")
      .post(rootPath+INVALID_LANG)
        .then()
          .statusCode(400)
          .body(containsString(INCORRECT_LANG_PARAMETER));

  }

  @Test
  public void testValidationOnGetById() {
    logger.info("=== Test validation Annotation on GET ORDER BY ID API ===");
    String id = "non-existent-po-id";

    logger.info("=== Test validation on invalid lang query parameter ===");
    RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .header(X_OKAPI_TOKEN)
        .contentType(APPLICATION_JSON)
      .get(rootPath+"/"+id+INVALID_LANG)
        .then()
          .statusCode(400)
          .body(containsString(INCORRECT_LANG_PARAMETER));

  }

  @Test
  public void testValidationDelete() {
    logger.info("=== Test validation Annotation on DELETE API ===");
    String id = "non-existent-po-id";

    logger.info("=== Test validation on invalid lang query parameter ===");
    RestAssured
     .with()
       .header(X_OKAPI_URL)
       .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
       .contentType(APPLICATION_JSON)
     .delete(rootPath+"/"+id+INVALID_LANG)
       .then()
         .statusCode(400)
         .body(containsString(INCORRECT_LANG_PARAMETER));

  }

  @Test
  public void testValidationOnPut() {
    logger.info("=== Test validation Annotation on PUT API ===");
    String id = "non-existent-po-id";
    logger.info("=== Test validation with no body ===");
    RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .contentType(APPLICATION_JSON)
      .put(rootPath+"/"+id)
        .then()
          .statusCode(400)
          .body(containsString("Json content error HV000116: The object to be validated must not be null"));

     logger.info("=== Test validation on invalid lang query parameter ===");
     RestAssured
       .with()
         .header(X_OKAPI_URL)
         .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
         .contentType(APPLICATION_JSON)
         .body("{}")
       .put(rootPath+"/"+id+INVALID_LANG)
         .then()
           .statusCode(400)
           .body(containsString(INCORRECT_LANG_PARAMETER));

     logger.info("=== Test validation on no Content-type parameter ===");
     RestAssured
       .with()
         .header(X_OKAPI_URL)
         .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
         .body("{}")
       .put(rootPath+"/"+id+INVALID_LANG)
         .then()
           .statusCode(400)
           .body(containsString("Content-type"));

  }

  @Test
  public void testDeleteOrderLineById() {
    logger.info("=== Test Delete Order Line By Id - Success case ===");

    deleteOrderLineByIdSuccess(PO_LINE_ID_FOR_SUCCESS_CASE);
  }

  @Test
  public void testDeleteOrderLineByIdWithPartiallyDeletedSubObjects() {
    logger.info("=== Test Delete Order Line By Id - Success case ===");

    // This test should behave the same as regular test and only warning in log expected
    deleteOrderLineByIdSuccess(PO_LINE_ID_WITH_SOME_SUB_OBJECTS_ALREADY_REMOVED);
  }

  private void deleteOrderLineByIdSuccess(String lineId) {
    String url = String.format(LINE_BY_ID_PATH, PO_ID_FOR_SUCCESS_CASE, lineId);

    final Response resp = verifyDeleteResponse(url, "", 204);
    assertTrue(StringUtils.isEmpty(resp.getBody().asString()));
  }

  @Test
  public void testDeleteOrderLineByIdWithoutOkapiUrlHeader() {
    logger.info("=== Test Delete Order Line By Id - 500 due to missing Okapi URL header ===");
    RestAssured
      .with()
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .delete(String.format(LINE_BY_ID_PATH, ID_DOES_NOT_EXIST, ID_DOES_NOT_EXIST))
        .then()
          .statusCode(500);
  }

  @Test
  public void testDeleteOrderLineByIdNotFound() {
    logger.info("=== Test Delete Order Line By Id - Not Found ===");

    String id = ID_DOES_NOT_EXIST;
    String url = String.format(LINE_BY_ID_PATH, ID_DOES_NOT_EXIST, id);
    Response actual = verifyDeleteResponse(url, TEXT_PLAIN, 404);

    assertEquals(id, actual.asString());
  }

  @Test
  public void testDeleteOrderLineById500FromStorageOnGetPoLine() {
    logger.info("=== Test Delete Order Line By Id - 500 From Storage On Get PO Line ===");

    String url = String.format(LINE_BY_ID_PATH, ID_DOES_NOT_EXIST, ID_FOR_INTERNAL_SERVER_ERROR);
    Response actual = verifyDeleteResponse(url, TEXT_PLAIN, 500);

    assertNotNull(actual.asString());
  }

  @Test
  public void testDeleteOrderLineById500FromStorageOnSubObjectDeletion() {
    logger.info("=== Test Delete Order Line By Id - 500 From Storage On Sub-Object deletion ===");

    String lineId = PO_LINE_ID_WITH_SUB_OBJECT_OPERATION_500_CODE;
    String orderId = getMockLine(lineId).getPurchaseOrderId();

    String url = String.format(LINE_BY_ID_PATH, orderId, lineId);
    Response actual = verifyDeleteResponse(url, TEXT_PLAIN, 500);

    assertNotNull(actual.asString());
  }

  private Response verifyDeleteResponse(String url, String expectedContentType, int expectedCode) {
    return RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .delete(url)
        .then()
          .contentType(expectedContentType)
          .statusCode(expectedCode)
          .extract()
            .response();
  }

  private Response verifyPostResponse(String url, String body, Header tenantHeader, String
    expectedContentType, int expectedCode) {
    return RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(X_OKAPI_TOKEN)
        .header(tenantHeader)
        .contentType(APPLICATION_JSON)
        .body(body)
      .post(url)
        .then()
          .log()
            .all()
          .contentType(expectedContentType)
          .statusCode(expectedCode)
          .extract()
            .response();
  }

  @Test
  public void testDeleteOrderLineByIdNotCorrespondingToOrderId() {
    logger.info("=== Test Delete Order Line By Id - Order line does not match PO id ===");

    final Response resp = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .delete(String.format(LINE_BY_ID_PATH, ID_DOES_NOT_EXIST, PO_LINE_ID_FOR_SUCCESS_CASE))
        .then()
          .contentType(ContentType.JSON)
          .statusCode(422)
          .extract()
            .response();

    JsonObject respBody = new JsonObject(resp.getBody().asString());
    logger.info(respBody.encodePrettily());

    assertNotNull(respBody.getValue("errors"));
    Errors errors = respBody.mapTo(Errors.class);

    assertEquals(1, errors.getErrors().size());
    String message = errors.getErrors()
                           .get(0)
                           .getMessage();
    assertNotNull(message);
    assertTrue(message.contains(ID_DOES_NOT_EXIST));
    assertTrue(message.contains(PO_LINE_ID_FOR_SUCCESS_CASE));
  }

  @Test
  public void testGetOrderLineById() {
    logger.info("=== Test Get Orderline By Id ===");

    final PoLine resp = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .get(String.format(LINE_BY_ID_PATH, PO_ID, ANOTHER_PO_LINE_ID_FOR_SUCCESS_CASE))
        .then()
          .statusCode(200)
          .extract()
          .response()
          .as(PoLine.class);

    logger.info(JsonObject.mapFrom(resp).encodePrettily());

    assertEquals(ANOTHER_PO_LINE_ID_FOR_SUCCESS_CASE, resp.getId());
  }

  @Test
  public void testGetOrderLineByIdError422() {
    logger.info("=== Test Get Orderline By Id - With 422 ===");

    String orderId = "Error422OrderId";

    final Response resp = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .get(String.format(LINE_BY_ID_PATH, orderId, ANOTHER_PO_LINE_ID_FOR_SUCCESS_CASE))
        .then()
          .statusCode(422)
          .extract()
          .response();

    logger.info(resp.prettyPrint());
    String msg = String.format("The PO line with id=%s does not belong to order with id=%s", ANOTHER_PO_LINE_ID_FOR_SUCCESS_CASE, orderId);
    assertEquals(resp.as(Errors.class).getErrors().get(0).getMessage(), msg);
  }

  @Test
  public void testGetOrderLineByIdWith404() {
    logger.info("=== Test Get Orderline By Id - With 404 ===");

    String orderId = "NoMatterId";
    String lineId = "NotExistingId";
    logger.info(String.format("using mock datafile: %s%s.json", BASE_MOCK_DATA_PATH, lineId));

    final Response resp = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .get(String.format(LINE_BY_ID_PATH, orderId, lineId))
        .then()
          .statusCode(404)
          .extract()
          .response();

    assertEquals(lineId, resp.getBody().asString());
  }

  @Test
  public void testGetOrderLineByIdWith500() {
    logger.info("=== Test Get Orderline By Id - With 500 ===");

    String orderId = "NoMatterId";
    String lineId = "generateError500";
    logger.info(String.format("using mock datafile: %s%s.json", BASE_MOCK_DATA_PATH, lineId));

    final Response resp = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
      .get(String.format(LINE_BY_ID_PATH, orderId, ID_FOR_INTERNAL_SERVER_ERROR))
        .then()
          .statusCode(500)
          .extract()
          .response();

    assertEquals("Internal Server Error", resp.getBody().print());
  }

  @Test
  public void testPostOrdersLinesById(TestContext ctx) {
    logger.info("=== Test Post Order Lines By Id (expected flow) ===");

    JsonObject compPoLineJson = getMockAsJson(COMP_PO_LINES_MOCK_DATA_PATH, ANOTHER_PO_LINE_ID_FOR_SUCCESS_CASE);
    String id = compPoLineJson.getString("purchase_order_id");
    final PoLine response = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .header(X_OKAPI_TOKEN)
        .contentType(APPLICATION_JSON)
        .body(compPoLineJson.encodePrettily())
      .post(String.format(LINES_PATH, id))
        .then()
          .contentType(APPLICATION_JSON)
          .statusCode(201)
          .extract()
          .response().as(PoLine.class);

    ctx.assertEquals(id, response.getPurchaseOrderId());
  }

  @Test
  public void testPostOrdersLinesByIdWithIdMismatch() {
    logger.info("=== Test Post Order Lines By Id (path and request body ids mismatching) ===");

    JsonObject body = getMockAsJson(COMP_PO_LINES_MOCK_DATA_PATH, ANOTHER_PO_LINE_ID_FOR_SUCCESS_CASE);
    RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .header(X_OKAPI_TOKEN)
        .contentType(APPLICATION_JSON)
        .body(body.encodePrettily())
      .post(String.format(LINES_PATH, ID_DOES_NOT_EXIST))
        .then()
          .contentType(TEXT_PLAIN)
          .statusCode(400)
          .extract()
          .response();
  }

  @Test
  public void testPostOrdersLinesByIdPoLineWithoutId(TestContext ctx) {
    logger.info("=== Test Post Order Lines By Id (empty id in body) ===");

    PoLine response = RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .header(X_OKAPI_TOKEN)
        .contentType(APPLICATION_JSON)
        .body("{}")
      .post(String.format(LINES_PATH, PO_ID))
        .then()
          .contentType(APPLICATION_JSON)
          .statusCode(201)
          .extract()
          .response().as(PoLine.class);

    ctx.assertEquals(PO_ID, response.getPurchaseOrderId());
  }

  @Test
  public void testPostOrdersLinesByIdStorageError() {
    logger.info("=== Test Post Order Lines By Id (mod-orders-storage error) ===");

    RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .header(X_OKAPI_TOKEN)
        .contentType(APPLICATION_JSON)
        .body("{}")
      .post(String.format(LINES_PATH, ID_FOR_INTERNAL_SERVER_ERROR))
        .then()
          .contentType(TEXT_PLAIN)
          .statusCode(500)
          .extract()
          .response();
  }

  @Test
  public void testValidationOnPutLineWithoutBody() {
    logger.info("=== Test validation on PUT line with no body ===");
    RestAssured
      .with()
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .contentType(APPLICATION_JSON)
      .put(String.format(LINE_BY_ID_PATH, ID_DOES_NOT_EXIST, ID_DOES_NOT_EXIST))
        .then()
          .statusCode(400)
          .body(containsString("Json content error HV000116: The object to be validated must not be null"));
  }

  @Test
  public void testValidationOnPutWithIncorrectLang() {
    logger.info("=== Test validation on PUT line with invalid lang query parameter ===");
    RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .contentType(APPLICATION_JSON)
        .body("{}")
      .put(String.format(LINE_BY_ID_PATH, ID_DOES_NOT_EXIST, ID_DOES_NOT_EXIST) + INVALID_LANG)
        .then()
          .statusCode(400)
          .body(containsString("'lang' parameter is incorrect. parameter value {english} is not valid: must match \"[a-zA-Z]{2}\""));
  }

  @Test
  public void testPutOrderLineById() {
    logger.info("=== Test PUT Order Line By Id - Success case ===");

    putOrderLineByIdSuccess(PO_LINE_ID_FOR_SUCCESS_CASE);
  }

  @Test
  public void testPutOrderLineByIdWithPartiallyDeletedSubObjects() {
    logger.info("=== Test PUT Order Line By Id - Line partially removed ===");

    // This test should behave the same as regular test and only warning in log expected
    putOrderLineByIdSuccess(PO_LINE_ID_WITH_SOME_SUB_OBJECTS_ALREADY_REMOVED);
  }

  private void putOrderLineByIdSuccess(String lineId) {
    String orderId = getMockLine(lineId).getPurchaseOrderId();

    String url = String.format(LINE_BY_ID_PATH, orderId, lineId);
    JsonObject body = getMockAsJson(COMP_PO_LINES_MOCK_DATA_PATH, lineId);

    final Response resp = verifyPut(url, body.encodePrettily(), "", 204);

    assertTrue(StringUtils.isEmpty(resp.getBody().asString()));
  }

  @Test
  public void testPutOrderLineByIdWithoutOkapiUrlHeader() {
    logger.info("=== Test PUT Order Line By Id - 500 due to missing Okapi URL header ===");

    String lineId = ID_DOES_NOT_EXIST;
    String orderId = ID_DOES_NOT_EXIST;

    String url = String.format(LINE_BY_ID_PATH, orderId, lineId);
    String body = String.format(EMPTY_PO_LINE_BUT_WITH_IDS, lineId, orderId);

    RestAssured
      .with()
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .contentType(APPLICATION_JSON)
        .body(body)
      .put(url)
        .then()
          .contentType(TEXT_PLAIN)
          .statusCode(500);
  }

  @Test
  public void testPutOrderLineByIdNotFound() {
    logger.info("=== Test PUT Order Line By Id - Not Found ===");

    String lineId = ID_DOES_NOT_EXIST;
    String orderId = PO_ID;

    String url = String.format(LINE_BY_ID_PATH, orderId, lineId);
    String body = String.format(EMPTY_PO_LINE_BUT_WITH_IDS, lineId, orderId);

    Response actual = verifyPut(url, body, TEXT_PLAIN, 404);

    assertEquals(lineId, actual.asString());
  }

  @Test
  public void testPutOrderLineByIdWithInvalidContentInBody() {
    logger.info("=== Test PUT Order Line By Id - Body Validation Error ===");

    String orderId = PO_ID;

    String url = String.format(LINE_BY_ID_PATH, orderId, ID_DOES_NOT_EXIST);
    String body = String.format(EMPTY_PO_LINE_BUT_WITH_IDS, ID_BAD_FORMAT, orderId);

    Response resp = verifyPut(url, body, APPLICATION_JSON, 422);

    assertEquals(1, resp.as(Errors.class).getErrors().size());
    assertNotNull(resp.as(Errors.class).getErrors().get(0).getMessage());
  }

  @Test
  public void testPutOrderLineByIdWithIdMismatch() {
    logger.info("=== Test PUT Order Line By Id - Ids mismatch ===");

    String orderId = PO_ID;

    String url = String.format(LINE_BY_ID_PATH, orderId, ID_DOES_NOT_EXIST);
    String body = String.format(EMPTY_PO_LINE_BUT_WITH_IDS, PO_ID_FOR_SUCCESS_CASE, orderId);

    Response resp = verifyPut(url, body, APPLICATION_JSON, 422);

    assertEquals(1, resp.as(Errors.class).getErrors().size());
    assertNotNull(resp.as(Errors.class).getErrors().get(0).getMessage());
  }

  @Test
  public void testPutOrderLineById500FromStorageOnGetPoLine() {
    logger.info("=== Test PUT Order Line By Id - 500 From Storage On Get PO Line ===");

    String lineId = ID_FOR_INTERNAL_SERVER_ERROR;
    String orderId = PO_ID;

    String url = String.format(LINE_BY_ID_PATH, orderId, lineId);
    String body = String.format(EMPTY_PO_LINE_BUT_WITH_IDS, lineId, orderId);

    Response actual = verifyPut(url, body, TEXT_PLAIN, 500);

    assertNotNull(actual.asString());
  }

  @Test
  public void testPutOrderLineById500FromStorageOnSubObjectDeletion() {
    logger.info("=== Test PUT Order Line By Id - 500 From Storage On Sub-Object deletion ===");

    String lineId = PO_LINE_ID_WITH_SUB_OBJECT_OPERATION_500_CODE;
    String orderId = getMockLine(lineId).getPurchaseOrderId();

    String url = String.format(LINE_BY_ID_PATH, orderId, lineId);
    String body = String.format(EMPTY_PO_LINE_BUT_WITH_IDS, lineId, orderId);
    Response actual = verifyPut(url, body, TEXT_PLAIN, 500);

    assertNotNull(actual.asString());
  }

  private org.folio.rest.acq.model.PoLine getMockLine(String id) {
    return getMockAsJson(PO_LINES_MOCK_DATA_PATH, id).mapTo(org.folio.rest.acq.model.PoLine.class);
  }

  private JsonObject getMockAsJson(String path, String id) {
    try {
      return new JsonObject(getMockData(String.format("%s%s.json", path, id)));
    } catch (IOException e) {
      fail(e.getMessage());
    }
    return new JsonObject();
  }

  private Response verifyPut(String url, String body, String expectedContentType, int expectedCode) {
    return RestAssured
      .with()
        .header(X_OKAPI_URL)
        .header(NON_EXIST_CONFIG_X_OKAPI_TENANT)
        .contentType(APPLICATION_JSON)
        .body(body)
      .put(url)
        .then()
          .contentType(expectedContentType)
          .statusCode(expectedCode)
          .extract()
            .response();
  }

  public static class MockServer {

    private static final Logger logger = LoggerFactory.getLogger(MockServer.class);

    final int port;
    final Vertx vertx;

    MockServer(int port) {
      this.port = port;
      this.vertx = Vertx.vertx();
    }

    void close() {
      vertx.close(res -> {
        if (res.failed()) {
          logger.error("Failed to shut down mock server", res.cause());
          fail(res.cause().getMessage());
        } else {
          logger.info("Successfully shut down mock server");
        }
      });
    }

    Router defineRoutes() {
      Router router = Router.router(vertx);

      router.route().handler(BodyHandler.create());
      router.route(HttpMethod.POST, "/purchase_order").handler(this::handlePostPurchaseOrder);
      router.route(HttpMethod.POST, "/po_line").handler(this::handlePostPOLine);
      router.route(HttpMethod.POST, "/adjustment").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.Adjustment.class));
      router.route(HttpMethod.POST, "/alert").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.Alert.class));
      router.route(HttpMethod.POST, "/cost").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.Cost.class));
      router.route(HttpMethod.POST, "/claim").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.Claim.class));
      router.route(HttpMethod.POST, "/details").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.Details.class));
      router.route(HttpMethod.POST, "/eresource").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.Eresource.class));
      router.route(HttpMethod.POST, "/location").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.Location.class));
      router.route(HttpMethod.POST, "/license").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.License.class));
      router.route(HttpMethod.POST, "/physical").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.Physical.class));
      router.route(HttpMethod.POST, "/renewal").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.Renewal.class));
      router.route(HttpMethod.POST, "/source").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.Source.class));
      router.route(HttpMethod.POST, "/reporting_code").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.ReportingCode.class));
      router.route(HttpMethod.POST, "/vendor_detail").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.VendorDetail.class));
      router.route(HttpMethod.POST, "/orders-storage/fund_distributions").handler(ctx -> handlePostGenericSubObj(ctx, org.folio.rest.acq.model.FundDistribution.class));

      router.route(HttpMethod.GET, "/purchase_order/:id").handler(this::handleGetPurchaseOrderById);
      router.route(HttpMethod.GET, "/po_line").handler(this::handleGetPoLines);
      router.route(HttpMethod.GET, "/po_line/:id").handler(this::handleGetPoLineById);
      router.route(HttpMethod.GET, "/adjustment/:id").handler(this::handleGetAdjustment);
      router.route(HttpMethod.GET, "/alert/:id").handler(this::handleGetGenericSubObj);
      router.route(HttpMethod.GET, "/cost/:id").handler(this::handleGetGenericSubObj);
      router.route(HttpMethod.GET, "/claim/:id").handler(this::handleGetGenericSubObj);
      router.route(HttpMethod.GET, "/details/:id").handler(this::handleGetGenericSubObj);
      router.route(HttpMethod.GET, "/eresource/:id").handler(this::handleGetGenericSubObj);
      router.route(HttpMethod.GET, "/orders-storage/fund_distributions/:id").handler(this::handleGetGenericSubObj);
      router.route(HttpMethod.GET, "/location/:id").handler(this::handleGetLocation);
      router.route(HttpMethod.GET, "/physical/:id").handler(this::handleGetGenericSubObj);
      router.route(HttpMethod.GET, "/renewal/:id").handler(this::handleGetGenericSubObj);
      router.route(HttpMethod.GET, "/source/:id").handler(this::handleGetGenericSubObj);
      router.route(HttpMethod.GET, "/vendor_detail/:id").handler(this::handleGetGenericSubObj);
      router.route(HttpMethod.GET, "/reporting_code/:id").handler(this::handleGetGenericSubObj);
      router.route(HttpMethod.GET, "/renewal/:id").handler(this::handleGetGenericSubObj);

      router.route(HttpMethod.DELETE, "/purchase_order/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/po_line/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/adjustment/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/cost/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/details/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/eresource/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/location/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/physical/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/renewal/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/source/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/vendor_detail/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/alert/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/claim/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/orders-storage/fund_distributions/:id").handler(this::handleDeleteGenericSubObj);
      router.route(HttpMethod.DELETE, "/reporting_code/:id").handler(this::handleDeleteGenericSubObj);

      router.get("/configurations/entries").handler(this::handleConfigurationModuleResponse);

      return router;
    }


    private void handleConfigurationModuleResponse(RoutingContext ctx) {
      try {
        String tenant = ctx.request().getHeader(OKAPI_HEADER_TENANT) ;
        serverResponse(ctx, 200, APPLICATION_JSON, getMockData(String.format(CONFIG_MOCK_PATH, tenant)));
      } catch (IOException e) {
        serverResponse(ctx, 500, TEXT_PLAIN, INTERNAL_SERVER_ERROR);
      }

    }

    private void handleDeleteGenericSubObj(RoutingContext ctx) {
      String id = ctx.request().getParam(ID);
      if (ID_DOES_NOT_EXIST.equals(id)) {
        serverResponse(ctx, 404, TEXT_PLAIN, id);
      } else if (ID_FOR_INTERNAL_SERVER_ERROR.equals(id)) {
        serverResponse(ctx, 500, TEXT_PLAIN, INTERNAL_SERVER_ERROR);
      } else {
        ctx.response()
           .setStatusCode(204)
           .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
           .end();
      }
    }

    void start(TestContext context) {
      // Setup Mock Server...
      HttpServer server = vertx.createHttpServer();

      final Async async = context.async();
      server.requestHandler(defineRoutes()::accept).listen(port, result -> {
        if (result.failed()) {
          logger.warn(result.cause());
        }
        context.assertTrue(result.succeeded());
        async.complete();
      });
    }

    private void handleGetPoLines(RoutingContext ctx) {
      logger.info("got: " + ctx.request().path());
      String id = ctx.request().getParam("query").split("purchase_order_id==")[1];
      String tenant = ctx.request().getHeader(OKAPI_HEADER_TENANT);

      if (ID_FOR_INTERNAL_SERVER_ERROR.equals(id)) {
        serverResponse(ctx, 500, TEXT_PLAIN, INTERNAL_SERVER_ERROR);
      } else {
        try {
          JsonObject compPO = new JsonObject(getMockData(String.format("%s%s.json", BASE_MOCK_DATA_PATH, id)));
          JsonArray lines = compPO.getJsonArray(PO_LINES);

          lines.forEach(l -> {
            JsonObject line = (JsonObject) l;
            line.put(ADJUSTMENT, ((Map<?, ?>) line.remove(ADJUSTMENT)).get(ID));
            line.put(COST, ((Map<?, ?>) line.remove(COST)).get(ID));
            line.put(DETAILS, ((Map<?, ?>) line.remove(DETAILS)).get(ID));
            line.put(ERESOURCE, ((Map<?, ?>) line.remove(ERESOURCE)).get(ID));
            line.put(LOCATION, ((Map<?, ?>) line.remove(LOCATION)).get(ID));
            line.put(PHYSICAL, ((Map<?, ?>) line.remove(PHYSICAL)).get(ID));
            if (line.containsKey(RENEWAL)) {
              line.put(RENEWAL, ((Map<?, ?>) line.remove(RENEWAL)).get(ID));
            }
            line.put(SOURCE, ((Map<?, ?>) line.remove(SOURCE)).get(ID));
            line.put(VENDOR_DETAIL, ((Map<?, ?>) line.remove(VENDOR_DETAIL)).get(ID));

            List<?> alerts = ((List<?>) line.remove(ALERTS));
            line.put(ALERTS, new JsonArray());
            alerts.forEach(a -> line.getJsonArray(ALERTS)
                                    .add(((Map<?, ?>) a).get(ID)));

            List<?> claims = ((List<?>) line.remove(CLAIMS));
            line.put(CLAIMS, new JsonArray());
            claims.forEach(c -> line.getJsonArray(CLAIMS)
                                    .add(((Map<?, ?>) c).get(ID)));

            List<?> fund_distribution = ((List<?>) line.remove(FUND_DISTRIBUTION));
            line.put(FUND_DISTRIBUTION, new JsonArray());
            fund_distribution.forEach(f -> line.getJsonArray(FUND_DISTRIBUTION)
                                               .add(((Map<?, ?>) f).get(ID)));
          });

          JsonObject po_lines = new JsonObject()
            .put(PO_LINES, lines)
            .put("first", 0)
            .put("last", lines.size());
          if (EMPTY_CONFIG_TENANT.equals(tenant)) {
            po_lines.put("total_records", DEFAULT_POLINE_LIMIT);
          } else {
            po_lines.put("total_records", lines.size());
          }


          logger.info(po_lines.encodePrettily());

          serverResponse(ctx, 200, APPLICATION_JSON, po_lines.encodePrettily());
        } catch (IOException e) {
          serverResponse(ctx, 404, TEXT_PLAIN, id);
        }
      }
    }

    private void handleGetPoLineById(RoutingContext ctx) {
      logger.info("got: " + ctx.request().path());
      String id = ctx.request().getParam(ID);
      logger.info("id: " + id);
      if (ID_FOR_INTERNAL_SERVER_ERROR.equals(id)) {
        serverResponse(ctx, 500, TEXT_PLAIN, INTERNAL_SERVER_ERROR);
      } else {
        try {
          JsonObject po = new JsonObject(getMockData(String.format("%s%s.json", PO_LINES_MOCK_DATA_PATH, id)));
          serverResponse(ctx, 200, APPLICATION_JSON, po.encodePrettily());
        } catch (IOException e) {
          serverResponse(ctx, 404, TEXT_PLAIN, id);
        }
      }
    }

    private void serverResponse(RoutingContext ctx, int statusCode, String contentType, String body) {
      ctx.response()
         .setStatusCode(statusCode)
         .putHeader(HttpHeaders.CONTENT_TYPE, contentType)
         .end(body);
    }

    private void handleGetGenericSubObj(RoutingContext ctx) {
      logger.info("got: " + ctx.request().path());
      String id = ctx.request().getParam(ID);
      logger.info("id: " + id);

      if (ID_DOES_NOT_EXIST.equals(id)) {
        serverResponse(ctx, 404, TEXT_PLAIN, id);
      } else if (ID_FOR_INTERNAL_SERVER_ERROR.equals(id)) {
        serverResponse(ctx, 500, TEXT_PLAIN, INTERNAL_SERVER_ERROR);
      } else {
        ctx.response()
           .setStatusCode(200)
           .putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
           .end(new JsonObject().put(ID, id).encodePrettily());
      }
    }

    private void handleGetAdjustment(RoutingContext ctx) {
      logger.info("got: " + ctx.request().path());
      String id = ctx.request().getParam(ID);
      logger.info("id: " + id);

      Adjustment a = new Adjustment();
      a.setId(id);
      a.setCredit(1d);
      a.setDiscount(2d);
      a.setInsurance(3d);
      a.setOverhead(4d);
      a.setShipment(5d);
      a.setTax1(6d);
      a.setTax2(7d);
      a.setInvoiceId(UUID.randomUUID().toString());

      ctx.response()
        .setStatusCode(200)
        .putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
        .end(JsonObject.mapFrom(a).encodePrettily());
    }

    private void handleGetPurchaseOrderById(RoutingContext ctx) {
      logger.info("got: " + ctx.request().path());
      String id = ctx.request().getParam(ID);
      logger.info("id: " + id);

      try {
        JsonObject po;
        if (shouldWorkWithTmpOrder(ctx)) {
          po = tmpOrder;
        } else {
          po = new JsonObject(getMockData(String.format("%s%s.json", BASE_MOCK_DATA_PATH, id)));
        }
        po.remove(ADJUSTMENT);
        po.remove(PO_LINES);
        po.put(ADJUSTMENT, UUID.randomUUID().toString());

        serverResponse(ctx, 200, APPLICATION_JSON, po.encodePrettily());
      } catch (IOException e) {
        ctx.response()
          .setStatusCode(404)
          .end(id);
      }
    }

    private void handlePostPurchaseOrder(RoutingContext ctx) {
      logger.info("got: " + ctx.getBodyAsString());

      org.folio.rest.acq.model.PurchaseOrder po = ctx.getBodyAsJson().mapTo(org.folio.rest.acq.model.PurchaseOrder.class);
      po.setId(UUID.randomUUID().toString());
      if (shouldWorkWithTmpOrder(ctx)) {
        tmpOrder = ctx.getBodyAsJson();
      }

      ctx.response()
        .setStatusCode(201)
        .putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
        .end(JsonObject.mapFrom(po).encodePrettily());
    }

    private void handlePostGenericSubObj(RoutingContext ctx, Class<?> clazz) {
      logger.info("got: " + ctx.getBodyAsString());

      String echoStatus = ctx.request().getHeader(X_ECHO_STATUS);

      int status = 201;
      String respBody = "";
      String contentType = TEXT_PLAIN;

      if (echoStatus != null) {
        try {
          status = Integer.parseInt(echoStatus);
        } catch (NumberFormatException e) {
          logger.error("Exception parsing " + X_ECHO_STATUS, e);
        }
      }
      ctx.response().setStatusCode(status);

      switch (status) {
        case 201:
          contentType = APPLICATION_JSON;
          JsonObject body = JsonObject.mapFrom(ctx.getBodyAsJson().mapTo(clazz)).put(ID, UUID.randomUUID().toString());
          respBody = body.encodePrettily();
          break;
        case 403:
          respBody = "Access requires permission: foo.bar.baz";
          break;
        case 500:
          respBody = INTERNAL_SERVER_ERROR;
          break;
      }

      serverResponse(ctx, status, contentType, respBody);
    }

    private void handlePostPOLine(RoutingContext ctx) {
      logger.info("got po_line: " + ctx.getBodyAsString());

      org.folio.rest.acq.model.PoLine pol = ctx.getBodyAsJson().mapTo(org.folio.rest.acq.model.PoLine.class);

      if (pol.getId() == null) {
        pol.setId(UUID.randomUUID().toString());
      }

      if (ID_FOR_INTERNAL_SERVER_ERROR.equals(pol.getPurchaseOrderId())) {
        ctx.response()
          .setStatusCode(500)
          .putHeader(HttpHeaders.CONTENT_TYPE, TEXT_PLAIN)
          .end();
      } else {
        ctx.response()
          .setStatusCode(201)
          .putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
          .end(JsonObject.mapFrom(pol).encodePrettily());
      }
    }

    private boolean shouldWorkWithTmpOrder(RoutingContext ctx) {
      return TMP_ORDER_HEADER.getValue().equals(ctx.request().getHeader(TMP_ORDER_HEADER.getName()));
    }

    private void handleGetLocation(RoutingContext ctx) {
      logger.info("got: " + ctx.request().path());
      String id = ctx.request().getParam(ID);
      logger.info("id: " + id);

      Location location = new Location();
      location.setId(id);
      location.setLocationId("123");
      location.setPoLineId("123");
      location.setQuantity(3);
      location.setQuantityElectronic(1);
      location.setQuantityPhysical(2);

      ctx.response()
        .setStatusCode(200)
        .putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
        .end(JsonObject.mapFrom(location).encodePrettily());
    }

  }

}
