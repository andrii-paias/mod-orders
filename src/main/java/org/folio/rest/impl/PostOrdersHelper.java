package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import me.escoffier.vertx.completablefuture.VertxCompletableFuture;
import org.folio.orders.rest.exceptions.HttpException;
import org.folio.orders.utils.HelperUtils;
import org.folio.rest.jaxrs.model.CompositePurchaseOrder;
import org.folio.rest.jaxrs.model.CompositePurchaseOrder.WorkflowStatus;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.PoLine;
import org.folio.rest.jaxrs.resource.Orders.PostOrdersResponse;
import org.folio.rest.tools.client.interfaces.HttpClientInterface;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.folio.orders.utils.SubObjects.ADJUSTMENT;
import static org.folio.orders.utils.SubObjects.PO_LINES;
import static org.folio.rest.RestVerticle.OKAPI_USERID_HEADER;

public class PostOrdersHelper {

  private static final Logger logger = LoggerFactory.getLogger(PostOrdersHelper.class);

  // epoch time @ 9/1/2018.
  // if you change this, you run the risk of duplicate poNumbers
  private static final long PO_NUMBER_EPOCH = 1535774400000L;

  private final HttpClientInterface httpClient;
  private final Context ctx;
  private final Handler<AsyncResult<javax.ws.rs.core.Response>> asyncResultHandler;
  private final Map<String, String> okapiHeaders;

  public PostOrdersHelper(HttpClientInterface httpClient, Map<String, String> okapiHeaders,
      Handler<AsyncResult<javax.ws.rs.core.Response>> asyncResultHandler, Context ctx) {
    this.httpClient = httpClient;
    this.okapiHeaders = okapiHeaders;
    this.ctx = ctx;
    this.asyncResultHandler = asyncResultHandler;
  }

  public CompletableFuture<CompositePurchaseOrder> createPOandPOLinesWithAutoGeneratedData(CompositePurchaseOrder compPO) {
      compPO.setCreatedBy(okapiHeaders.get(OKAPI_USERID_HEADER));
      compPO.setPoNumber(generatePoNumber());
      compPO.setCreated(new Date());
      return createPOandPOLines(compPO);

  }

  public CompletableFuture<CompositePurchaseOrder> createPOandPOLines(CompositePurchaseOrder compPO) {
    CompletableFuture<CompositePurchaseOrder> future = new VertxCompletableFuture<>(ctx);

    try {
      JsonObject purchaseOrder = JsonObject.mapFrom(compPO);
      if (purchaseOrder.containsKey(ADJUSTMENT)) {
        purchaseOrder.remove(ADJUSTMENT);
      }
      if (purchaseOrder.containsKey(PO_LINES)) {
        purchaseOrder.remove(PO_LINES);
      }
      httpClient.request(HttpMethod.POST, purchaseOrder, "/purchase_order", okapiHeaders)
        .thenApply(HelperUtils::verifyAndExtractBody)
        .thenAccept(poBody -> {
          CompositePurchaseOrder po = poBody.mapTo(CompositePurchaseOrder.class);
          String poNumber = po.getPoNumber();
          String poId = po.getId();
          compPO.setId(poId);

          List<PoLine> lines = new ArrayList<>(compPO.getPoLines().size());
          List<CompletableFuture<Void>> futures = new ArrayList<>();
          boolean updateInventory = compPO.getWorkflowStatus() == WorkflowStatus.OPEN;
          for (int i = 0; i < compPO.getPoLines().size(); i++) {
            PoLine compPOL = compPO.getPoLines().get(i);
            compPOL.setPurchaseOrderId(poId);
            compPOL.setPoLineNumber(poNumber + "-" + (i + 1));

            futures.add(new PostOrderLineHelper(httpClient, okapiHeaders, asyncResultHandler, ctx)
              .createPoLineWithAutoGeneratedData(compPOL, updateInventory)
              .thenAccept(lines::add));
          }

          VertxCompletableFuture.allOf(ctx, futures.toArray(new CompletableFuture[0]))
            .thenAccept(v -> {
              compPO.setPoLines(lines);
              compPO.setAdjustment(HelperUtils.calculateAdjustment(lines));
              future.complete(compPO);
            })
            .exceptionally(e -> {
              future.completeExceptionally(e.getCause());
              return null;
            });
        })
        .exceptionally(e -> {
          future.completeExceptionally(e.getCause());
          return null;
        });
    } catch (Exception e) {
      logger.error("Exception calling POST /purchase_order", e);
      future.completeExceptionally(e);
    }
    return future;
  }

  public CompletableFuture<CompositePurchaseOrder> applyFunds(CompositePurchaseOrder compPO) {
    CompletableFuture<CompositePurchaseOrder> future = new VertxCompletableFuture<>(ctx);
    future.complete(compPO);
    return future;
  }

  public CompletableFuture<CompositePurchaseOrder> updateInventory(CompositePurchaseOrder compPO) {
    CompletableFuture<CompositePurchaseOrder> future = new VertxCompletableFuture<>(ctx);
    future.complete(compPO);
    return future;
  }

  public Void handleError(Throwable throwable) {
    final Future<javax.ws.rs.core.Response> result;

    logger.error("Exception placing order", throwable.getCause());

    final Throwable t = throwable.getCause();
    if (t instanceof HttpException) {
      final int code = ((HttpException) t).getCode();
      final String message = t.getMessage();
      switch (code) {
      case 400:
        result = Future.succeededFuture(PostOrdersResponse.respond400WithTextPlain(message));
        break;
      case 401:
        result = Future.succeededFuture(PostOrdersResponse.respond401WithTextPlain(message));
        break;
      case 422:
        Errors errors = new Errors();
        errors.getErrors()
          .add(new Error().withMessage(message));
        result = Future.succeededFuture(PostOrdersResponse.respond422WithApplicationJson(errors));
        break;
      default:
        result = Future.succeededFuture(PostOrdersResponse.respond500WithTextPlain(message));
      }
    } else {
      result = Future.succeededFuture(PostOrdersResponse.respond500WithTextPlain(throwable.getMessage()));
    }

    httpClient.closeClient();

    asyncResultHandler.handle(result);

    return null;
  }

  private static String generatePoNumber() {
    return (Long.toHexString(System.currentTimeMillis() - PO_NUMBER_EPOCH) +
        Long.toHexString(System.nanoTime() % 100))
          .toUpperCase();
  }

}
