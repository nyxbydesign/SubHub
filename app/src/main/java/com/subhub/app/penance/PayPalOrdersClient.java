package com.subhub.app.penance;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Environment-bound PayPal Orders v2 client with strict verification and bounded retries. */
public final class PayPalOrdersClient {
    private static final String RETURN_URL = "subhubapp://paypal/checkout/return";
    private static final String CANCEL_URL = "subhubapp://paypal/checkout/cancel";
    private static final String VAULT_RETURN_URL = "subhubapp://paypal/vault/return";
    private static final String VAULT_CANCEL_URL = "subhubapp://paypal/vault/cancel";
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PayPalRiskDataCollector riskData;

    public PayPalOrdersClient(Context context) {
        riskData = new PayPalRiskDataCollector(context);
    }

    /** Verifies merchant credentials against PayPal without creating an order or payer session. */
    public void validateCredentials(PayPalCredentialStore.Credentials credentials,
            Callback<Boolean> callback) {
        network.execute(() -> {
            try {
                accessToken(credentials);
                deliver(callback, Result.success(Boolean.TRUE));
            } catch (Exception error) {
                deliver(callback, Result.failure(safeMessage(error), classify(error)));
            }
        });
    }

    public void createOrder(PayPalCredentialStore.Credentials credentials,
            String settlementId, int amountCents, Callback<Order> callback) {
        createOrder(credentials, settlementId, amountCents, false, callback);
    }

    public void createOrder(PayPalCredentialStore.Credentials credentials,
            String settlementId, int amountCents, boolean requestVault,
            Callback<Order> callback) {
        createOrder(credentials, settlementId, amountCents, requestVault,
                Collections.emptyList(), callback);
    }

    public void createOrder(PayPalCredentialStore.Credentials credentials,
            String settlementId, int amountCents, boolean requestVault,
            List<OrderItem> orderItems, Callback<Order> callback) {
        createOrder(credentials, settlementId, amountCents, PenanceManager.CURRENCY,
                requestVault, orderItems, callback);
    }

    public void createOrder(PayPalCredentialStore.Credentials credentials,
            String settlementId, int amountCents, String currency, boolean requestVault,
            List<OrderItem> orderItems, Callback<Order> callback) {
        WalletCurrency.requireSupported(currency);
        List<OrderItem> items = orderItems == null
                ? Collections.emptyList() : new ArrayList<>(orderItems);
        network.execute(() -> {
            try {
                String clientMetadataId = riskData.collect(
                        credentials.clientId(), credentials.environment());
                if (clientMetadataId.isEmpty()) {
                    throw new IllegalStateException("PayPal risk data was unavailable");
                }
                String token = accessToken(credentials);
                JSONObject amount = new JSONObject()
                        .put("currency_code", currency)
                        .put("value", decimalAmount(amountCents));
                JSONObject unit = new JSONObject()
                        .put("reference_id", settlementId)
                        .put("custom_id", settlementId)
                        .put("amount", amount);
                addOrderItems(unit, amount, amountCents, currency, items);
                boolean vaultRequested = requestVault;
                JSONObject response;
                try {
                    response = request(credentials.environment(), "POST",
                            "/v2/checkout/orders", token,
                            createOrderBody(unit, clientMetadataId, vaultRequested).toString(),
                            vaultRequested
                                    ? PayPalRequestPolicy.createRequestId(settlementId)
                                    : PayPalRequestPolicy.standardCreateRequestId(settlementId),
                            clientMetadataId);
                } catch (PayPalApiException apiError) {
                    if (!vaultRequested || !PayPalVaultPolicy.shouldRetryWithoutVault(
                            apiError.status, apiError.context)) {
                        throw apiError;
                    }
                    response = request(credentials.environment(), "POST",
                            "/v2/checkout/orders", token,
                            createOrderBody(unit, clientMetadataId, false).toString(),
                            PayPalRequestPolicy.standardCreateRequestId(settlementId),
                            clientMetadataId);
                    vaultRequested = false;
                }
                String orderId = response.optString("id", "");
                String approvalUrl = link(response.optJSONArray("links"), "approve");
                if (approvalUrl.isEmpty()) {
                    approvalUrl = link(response.optJSONArray("links"), "payer-action");
                }
                if (orderId.isEmpty() || approvalUrl.isEmpty()) {
                    throw new IllegalStateException("PayPal order response was incomplete");
                }
                deliver(callback, Result.success(
                        new Order(orderId, approvalUrl, clientMetadataId, vaultRequested)));
            } catch (Exception error) {
                deliver(callback, Result.failure(safeMessage(error), classify(error)));
            }
        });
    }

    /** Starts a payer-present PayPal Wallet authorization without creating a charge. */
    public void createVaultSetupToken(PayPalCredentialStore.Credentials credentials,
            String existingCustomerId, Callback<VaultSetup> callback) {
        network.execute(() -> {
            try {
                String clientMetadataId = riskData.collect(
                        credentials.clientId(), credentials.environment());
                if (clientMetadataId.isEmpty()) {
                    throw new IllegalStateException("PayPal risk data was unavailable");
                }
                String token = accessToken(credentials);
                JSONObject experience = new JSONObject()
                        .put("shipping_preference", "NO_SHIPPING")
                        .put("brand_name", "SubHub")
                        .put("user_action", "SETUP_NOW")
                        .put("return_url", callbackUrl(VAULT_RETURN_URL, clientMetadataId))
                        .put("cancel_url", callbackUrl(VAULT_CANCEL_URL, clientMetadataId));
                JSONObject paypal = new JSONObject()
                        .put("description", "SubHub payer wallet")
                        .put("permit_multiple_payment_tokens", false)
                        .put("usage_pattern", "UNSCHEDULED_POSTPAID")
                        .put("usage_type", "MERCHANT")
                        .put("customer_type", "CONSUMER")
                        .put("experience_context", experience);
                JSONObject body = new JSONObject().put("payment_source",
                        new JSONObject().put("paypal", paypal));
                String cleanCustomerId = existingCustomerId == null
                        ? "" : existingCustomerId.trim();
                if (!cleanCustomerId.isEmpty()) {
                    body.put("customer", new JSONObject().put("id", cleanCustomerId));
                }
                JSONObject response = request(credentials.environment(), "POST",
                        "/v3/vault/setup-tokens", token, body.toString(),
                        PayPalRequestPolicy.vaultSetupRequestId(
                                UUID.randomUUID().toString()), clientMetadataId);
                String setupTokenId = response.optString("id", "");
                JSONObject customer = response.optJSONObject("customer");
                String customerId = customer == null
                        ? cleanCustomerId : customer.optString("id", cleanCustomerId);
                String approvalUrl = link(response.optJSONArray("links"), "approve");
                if (setupTokenId.isEmpty() || approvalUrl.isEmpty()) {
                    throw new IllegalStateException(
                            "PayPal wallet authorization response was incomplete");
                }
                deliver(callback, Result.success(new VaultSetup(setupTokenId,
                        approvalUrl, customerId, clientMetadataId)));
            } catch (Exception error) {
                deliver(callback, Result.failure(safeMessage(error), classifyVault(error)));
            }
        });
    }

    /** Exchanges a payer-approved setup token for the reusable PayPal payment token. */
    public void confirmVaultSetupToken(PayPalCredentialStore.Credentials credentials,
            String setupTokenId, String clientMetadataId,
            Callback<PaymentToken> callback) {
        network.execute(() -> {
            try {
                String cleanSetupToken = setupTokenId == null ? "" : setupTokenId.trim();
                if (cleanSetupToken.isEmpty()) {
                    throw new IllegalStateException("PayPal wallet authorization is missing");
                }
                String metadata = clientMetadataId == null ? "" : clientMetadataId.trim();
                if (metadata.isEmpty()) {
                    metadata = riskData.collect(
                            credentials.clientId(), credentials.environment());
                }
                String token = accessToken(credentials);
                JSONObject body = new JSONObject().put("payment_source",
                        new JSONObject().put("token", new JSONObject()
                                .put("id", cleanSetupToken)
                                .put("type", "SETUP_TOKEN")));
                JSONObject response = request(credentials.environment(), "POST",
                        "/v3/vault/payment-tokens", token, body.toString(),
                        PayPalRequestPolicy.vaultConfirmRequestId(cleanSetupToken), metadata);
                String paymentTokenId = response.optString("id", "");
                JSONObject customer = response.optJSONObject("customer");
                String customerId = customer == null ? "" : customer.optString("id", "");
                JSONObject source = response.optJSONObject("payment_source");
                JSONObject paypal = source == null ? null : source.optJSONObject("paypal");
                String payerEmail = paypal == null
                        ? "" : paypal.optString("email_address", "");
                String payerAccountId = paypal == null
                        ? "" : paypal.optString("payer_id", paypal.optString("account_id", ""));
                if (paymentTokenId.isEmpty() || customerId.isEmpty()) {
                    throw new IllegalStateException("PayPal did not return a saved wallet token");
                }
                deliver(callback, Result.success(new PaymentToken(paymentTokenId,
                        customerId, payerEmail, payerAccountId)));
            } catch (Exception error) {
                deliver(callback, Result.failure(safeMessage(error), classify(error)));
            }
        });
    }

    /** Reads the server-side setup-token state after PayPal's approval page closes. */
    public void getVaultSetupToken(PayPalCredentialStore.Credentials credentials,
            String setupTokenId, String clientMetadataId,
            Callback<VaultSetupStatus> callback) {
        network.execute(() -> {
            try {
                String cleanSetupToken = setupTokenId == null ? "" : setupTokenId.trim();
                if (cleanSetupToken.isEmpty()) {
                    throw new IllegalStateException("PayPal wallet authorization is missing");
                }
                String token = accessToken(credentials);
                JSONObject response = request(credentials.environment(), "GET",
                        "/v3/vault/setup-tokens/"
                                + android.net.Uri.encode(cleanSetupToken),
                        token, "", "", clientMetadataId);
                String status = response.optString("status", "");
                JSONObject customer = response.optJSONObject("customer");
                String customerId = customer == null
                        ? "" : customer.optString("id", "");
                boolean confirmable = PayPalVaultPolicy.isSetupApproved(status);
                deliver(callback, Result.success(
                        new VaultSetupStatus(status, customerId, confirmable)));
            } catch (Exception error) {
                deliver(callback, Result.failure(safeMessage(error), classifyVault(error)));
            }
        });
    }

    private static JSONObject createOrderBody(JSONObject unit, String clientMetadataId,
            boolean requestVault) throws Exception {
        JSONObject experience = new JSONObject()
                .put("user_action", "PAY_NOW")
                .put("shipping_preference", "NO_SHIPPING")
                .put("return_url", callbackUrl(RETURN_URL, clientMetadataId))
                .put("cancel_url", callbackUrl(CANCEL_URL, clientMetadataId));
        JSONObject paypal = new JSONObject().put("experience_context", experience);
        if (requestVault) {
            paypal.put("attributes", new JSONObject().put("vault",
                    new JSONObject()
                            .put("store_in_vault", "ON_SUCCESS")
                            .put("usage_type", "MERCHANT")
                            .put("usage_pattern", "UNSCHEDULED_POSTPAID")
                            .put("customer_type", "CONSUMER")));
        }
        return new JSONObject()
                .put("intent", "CAPTURE")
                .put("purchase_units", new JSONArray().put(unit))
                .put("payment_source", new JSONObject().put("paypal", paypal));
    }

    public void captureOrder(PayPalCredentialStore.Credentials credentials,
            String orderId, String settlementId, int expectedAmountCents,
            String clientMetadataId, Callback<Capture> callback) {
        captureOrder(credentials, orderId, settlementId, expectedAmountCents,
                PenanceManager.CURRENCY, clientMetadataId, callback);
    }

    public void captureOrder(PayPalCredentialStore.Credentials credentials,
            String orderId, String settlementId, int expectedAmountCents, String currency,
            String clientMetadataId, Callback<Capture> callback) {
        WalletCurrency.requireSupported(currency);
        network.execute(() -> {
            try {
                String token = accessToken(credentials);
                String metadata = clientMetadataId == null ? "" : clientMetadataId.trim();
                if (metadata.isEmpty()) {
                    metadata = riskData.collect(
                            credentials.clientId(), credentials.environment());
                }
                if (metadata.isEmpty()) {
                    throw new IllegalStateException("PayPal risk data was unavailable");
                }
                JSONObject response = request(credentials.environment(), "POST",
                        "/v2/checkout/orders/" + orderId + "/capture", token, "{}",
                        PayPalRequestPolicy.captureRequestId(settlementId), metadata);
                deliver(callback, Result.success(parseCapture(
                        response, settlementId, expectedAmountCents, currency)));
            } catch (Exception error) {
                deliver(callback, Result.failure(safeMessage(error), classify(error)));
            }
        });
    }

    /** Captures a saved PayPal wallet for explicitly authorized timed protection. */
    public void createStoredWalletPayment(PayPalCredentialStore.Credentials credentials,
            String settlementId, int amountCents, String vaultId,
            Callback<Capture> callback) {
        createStoredWalletPayment(credentials, settlementId, amountCents,
                PenanceManager.CURRENCY, vaultId, callback);
    }

    public void createStoredWalletPayment(PayPalCredentialStore.Credentials credentials,
            String settlementId, int amountCents, String currency, String vaultId,
            Callback<Capture> callback) {
        WalletCurrency.requireSupported(currency);
        network.execute(() -> {
            try {
                PayPalRequestPolicy.StoredWalletRequest stored;
                try {
                    stored = PayPalRequestPolicy.storedWalletRequest(vaultId);
                } catch (IllegalArgumentException missingToken) {
                    throw new ReauthorizationRequiredException(
                            "The saved PayPal wallet must be linked again");
                }
                String clientMetadataId = riskData.collect(
                        credentials.clientId(), credentials.environment());
                if (clientMetadataId.isEmpty()) {
                    throw new IllegalStateException("PayPal risk data was unavailable");
                }
                String token = accessToken(credentials);
                JSONObject unit = new JSONObject()
                        .put("reference_id", settlementId)
                        .put("custom_id", settlementId)
                        .put("amount", new JSONObject()
                                .put("currency_code", currency)
                                .put("value", decimalAmount(amountCents)));
                JSONObject paypal = new JSONObject()
                        .put("vault_id", stored.vaultId())
                        .put("stored_credential", new JSONObject()
                                .put("payment_initiator", stored.paymentInitiator())
                                .put("usage", stored.usage())
                                .put("usage_pattern", stored.usagePattern()));
                JSONObject body = new JSONObject()
                        .put("intent", "CAPTURE")
                        .put("purchase_units", new JSONArray().put(unit))
                        .put("payment_source", new JSONObject().put("paypal", paypal));
                JSONObject response = request(credentials.environment(), "POST",
                        "/v2/checkout/orders", token, body.toString(),
                        PayPalRequestPolicy.autoRequestId(settlementId), clientMetadataId);
                String status = response.optString("status", "");
                PayPalRequestPolicy.StoredWalletOutcome outcome =
                        PayPalRequestPolicy.storedWalletOutcome(status,
                                !link(response.optJSONArray("links"), "payer-action").isEmpty(),
                                !link(response.optJSONArray("links"), "approve").isEmpty());
                if (outcome == PayPalRequestPolicy.StoredWalletOutcome.REAUTHORIZATION_REQUIRED) {
                    throw new ReauthorizationRequiredException(
                            "PayPal requires the wallet owner to approve again");
                }
                if (outcome != PayPalRequestPolicy.StoredWalletOutcome.COMPLETED) {
                    throw new IllegalStateException(
                            "PayPal did not complete the automatic wallet payment");
                }
                deliver(callback, Result.success(parseCapture(
                        response, settlementId, amountCents, currency)));
            } catch (Exception error) {
                deliver(callback, Result.failure(safeMessage(error), classify(error)));
            }
        });
    }

    static void addOrderItems(JSONObject unit, JSONObject amount, int amountCents,
            String currency, List<OrderItem> items) throws Exception {
        if (items == null || items.isEmpty()) return;
        if (items.size() > 200) {
            throw new IllegalArgumentException("PayPal order contains too many ledger entries");
        }
        int itemTotal = 0;
        JSONArray encoded = new JSONArray();
        for (OrderItem item : items) {
            if (item == null || item.amountCents <= 0) continue;
            itemTotal = Math.addExact(itemTotal, item.amountCents);
            JSONObject encodedItem = new JSONObject()
                    .put("name", bounded(item.name, 127, "SubHub tribute"))
                    .put("description", bounded(item.description, 2048, ""))
                    .put("unit_amount", new JSONObject()
                            .put("currency_code", currency)
                            .put("value", decimalAmount(item.amountCents)))
                    .put("quantity", "1");
            encoded.put(encodedItem);
        }
        if (encoded.length() == 0) return;
        if (itemTotal != amountCents) {
            throw new IllegalArgumentException("PayPal ledger items do not match the total");
        }
        amount.put("breakdown", new JSONObject().put("item_total", new JSONObject()
                .put("currency_code", currency)
                .put("value", decimalAmount(itemTotal))));
        unit.put("items", encoded);
    }

    private static String bounded(String value, int maximum, String fallback) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) clean = fallback;
        return clean.length() <= maximum ? clean : clean.substring(0, maximum);
    }

    static Capture parseCapture(JSONObject response, String settlementId,
            int expectedAmountCents, String currency) throws Exception {
        if (!"COMPLETED".equalsIgnoreCase(response.optString("status"))) {
            throw new IllegalStateException("PayPal order is not completed");
        }
        JSONArray units = response.optJSONArray("purchase_units");
        JSONObject unit = units == null || units.length() != 1
                ? null : units.optJSONObject(0);
        if (unit == null || !settlementId.equals(unit.optString("custom_id"))) {
            throw new IllegalStateException("PayPal settlement reference did not match");
        }
        JSONObject payments = unit.optJSONObject("payments");
        JSONArray captures = payments == null ? null : payments.optJSONArray("captures");
        JSONObject capture = captures == null || captures.length() != 1
                ? null : captures.optJSONObject(0);
        if (capture == null || !"COMPLETED".equalsIgnoreCase(capture.optString("status"))) {
            throw new IllegalStateException("PayPal capture is not completed");
        }
        JSONObject amount = capture.optJSONObject("amount");
        if (amount == null
                || !currency.equals(amount.optString("currency_code"))
                || expectedAmountCents != cents(amount.optString("value"))) {
            throw new IllegalStateException("PayPal capture amount did not match");
        }
        JSONObject source = response.optJSONObject("payment_source");
        JSONObject paypal = source == null ? null : source.optJSONObject("paypal");
        JSONObject attributes = paypal == null ? null : paypal.optJSONObject("attributes");
        if (attributes == null && paypal != null) attributes = paypal.optJSONObject("attribute");
        JSONObject vault = attributes == null ? null : attributes.optJSONObject("vault");
        JSONObject customer = vault == null ? null : vault.optJSONObject("customer");
        JSONObject payer = response.optJSONObject("payer");
        String payerEmail = paypal == null ? "" : paypal.optString("email_address", "");
        String payerAccountId = paypal == null ? "" : paypal.optString("account_id", "");
        if (payerEmail.isEmpty() && payer != null) {
            payerEmail = payer.optString("email_address", "");
        }
        if (payerAccountId.isEmpty() && payer != null) {
            payerAccountId = payer.optString("payer_id", "");
        }
        return new Capture(capture.optString("id"),
                vault == null ? "" : vault.optString("status"),
                vault == null ? "" : vault.optString("id"),
                customer == null ? "" : customer.optString("id"),
                payerEmail, payerAccountId);
    }

    /** Optional merchant metadata; never creates a payer session or payment. */
    public void readPrimaryCurrency(PayPalCredentialStore.Credentials credentials,
            Callback<String> callback) {
        network.execute(() -> {
            try {
                JSONObject response = request(credentials.environment(), "GET",
                        "/v1/reporting/balances", accessToken(credentials), "", "", "");
                deliver(callback, Result.success(primaryCurrency(response)));
            } catch (Exception error) {
                deliver(callback, Result.failure("Primary currency unavailable", classify(error)));
            }
        });
    }

    static String primaryCurrency(JSONObject response) {
        JSONArray balances = response.optJSONArray("balances");
        String primary = "";
        int count = 0;
        if (balances == null) return "";
        for (int index = 0; index < balances.length(); index++) {
            JSONObject balance = balances.optJSONObject(index);
            if (balance == null || !Boolean.TRUE.equals(balance.opt("primary"))) continue;
            count++;
            primary = balance.optString("currency", "");
        }
        return count == 1 && WalletCurrency.isSupported(primary) ? primary : "";
    }

    private String accessToken(PayPalCredentialStore.Credentials credentials)
            throws Exception {
        if (!credentials.isComplete()) throw new IllegalStateException("PayPal credentials missing");
        Exception latest = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return accessTokenOnce(credentials);
            } catch (Exception error) {
                latest = error;
                if (attempt > 0 || !retryable(error)) throw error;
                pauseForRetry();
            }
        }
        throw latest == null ? new IOException("PayPal authentication failed") : latest;
    }

    private String accessTokenOnce(PayPalCredentialStore.Credentials credentials)
            throws Exception {
        String basic = credentials.clientId() + ":" + credentials.secret();
        HttpURLConnection connection = connection(
                credentials.environment(), "/v1/oauth2/token", "POST");
        connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(
                basic.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        write(connection, "grant_type=client_credentials");
        JSONObject response = response(connection);
        String token = response.optString("access_token", "");
        if (token.isEmpty()) throw new IllegalStateException("PayPal authentication failed");
        return token;
    }

    private JSONObject request(PayPalEnvironment environment, String method,
            String path, String token, String body,
            String requestId, String clientMetadataId) throws Exception {
        Exception latest = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return requestOnce(environment, method, path, token, body,
                        requestId, clientMetadataId);
            } catch (Exception error) {
                latest = error;
                if (attempt > 0 || !retryable(error)) throw error;
                pauseForRetry();
            }
        }
        throw latest == null ? new IOException("PayPal request failed") : latest;
    }

    private JSONObject requestOnce(PayPalEnvironment environment, String method,
            String path, String token, String body,
            String requestId, String clientMetadataId) throws Exception {
        HttpURLConnection connection = connection(environment, path, method);
        boolean writesBody = PayPalRequestPolicy.hasRequestBody(method, body);
        connection.setDoOutput(writesBody);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Prefer", "return=representation");
        if (requestId != null && !requestId.isEmpty()) {
            connection.setRequestProperty("PayPal-Request-Id", requestId);
        }
        if (clientMetadataId != null && !clientMetadataId.isEmpty()) {
            connection.setRequestProperty("PayPal-Client-Metadata-Id", clientMetadataId);
        }
        if (writesBody) write(connection, body);
        return response(connection);
    }

    private static HttpURLConnection connection(PayPalEnvironment environment,
            String path, String method) throws Exception {
        PayPalEnvironment selected = environment == null
                ? PayPalEnvironment.SANDBOX : environment;
        URL url = new URL(selected.apiRoot() + path);
        if (!selected.apiHost().equalsIgnoreCase(url.getHost())) {
            throw new IllegalStateException("PayPal environment boundary mismatch");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Language", "en_US");
        return connection;
    }

    private static void write(HttpURLConnection connection, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
    }

    private static JSONObject response(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        String debugId = connection.getHeaderField("PayPal-Debug-Id");
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String body = read(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            String detail = "PayPal returned HTTP " + status;
            String issue = "";
            StringBuilder context = new StringBuilder();
            try {
                JSONObject error = new JSONObject(body);
                appendContext(context, error.optString("name", ""));
                appendContext(context, error.optString("message", ""));
                JSONArray details = error.optJSONArray("details");
                String description = "";
                if (details != null) {
                    for (int index = 0; index < details.length(); index++) {
                        JSONObject item = details.optJSONObject(index);
                        if (item == null) continue;
                        String itemIssue = item.optString("issue", "");
                        String itemDescription = item.optString("description", "");
                        if (issue.isEmpty()) issue = itemIssue;
                        if (description.isEmpty()) description = itemDescription;
                        appendContext(context, itemIssue);
                        appendContext(context, item.optString("field", ""));
                        appendContext(context, itemDescription);
                    }
                }
                if (issue.isEmpty()) issue = error.optString("name", "");
                if (!issue.isEmpty()) detail += ": " + issue;
                if (!description.isEmpty()) detail += " — " + description;
                else {
                    String message = error.optString("message", "");
                    if (!message.isEmpty()) detail += ": " + message;
                }
            } catch (Exception ignored) {}
            if (debugId != null && !debugId.isEmpty()) detail += " · debug " + debugId;
            throw new PayPalApiException(status, issue, context.toString(), detail);
        }
        return body.isEmpty() ? new JSONObject() : new JSONObject(body);
    }

    private static void appendContext(StringBuilder target, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (target.length() > 0) target.append(' ');
        target.append(value.trim());
    }

    private static boolean retryable(Exception error) {
        if (error instanceof PayPalApiException) {
            int status = ((PayPalApiException) error).status;
            return PayPalRequestPolicy.isTransientStatus(status);
        }
        return error instanceof IOException;
    }

    private static void pauseForRetry() throws InterruptedException {
        try {
            Thread.sleep(350L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1 && total < 262_144) {
                int accepted = Math.min(count, 262_144 - total);
                output.write(buffer, 0, accepted);
                total += accepted;
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String link(JSONArray links, String relation) {
        if (links == null) return "";
        for (int index = 0; index < links.length(); index++) {
            JSONObject link = links.optJSONObject(index);
            if (link != null && relation.equals(link.optString("rel"))) {
                return link.optString("href", "");
            }
        }
        return "";
    }

    private static String decimalAmount(int amountCents) {
        return String.format(Locale.ROOT, "%.2f", amountCents / 100.0);
    }

    private static String callbackUrl(String base, String clientMetadataId) {
        return base + "?cmid=" + android.net.Uri.encode(clientMetadataId);
    }

    private static int cents(String decimal) {
        return new java.math.BigDecimal(decimal).movePointRight(2).intValueExact();
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "PayPal request failed" : message;
    }

    private static ErrorKind classify(Exception error) {
        if (error instanceof ReauthorizationRequiredException) {
            return ErrorKind.REAUTHORIZATION_REQUIRED;
        }
        if (error instanceof PayPalApiException) {
            PayPalApiException api = (PayPalApiException) error;
            if (PayPalVaultPolicy.isUnavailableIssue(api.issue)
                    || PayPalVaultPolicy.shouldRetryWithoutVault(api.status, api.context)) {
                return ErrorKind.VAULT_UNAVAILABLE;
            }
            if (reauthorizationIssue(api.issue)) {
                return ErrorKind.REAUTHORIZATION_REQUIRED;
            }
            if (api.status == 401 || api.status == 403) return ErrorKind.AUTHENTICATION;
            if (PayPalRequestPolicy.isTransientStatus(api.status)) return ErrorKind.TRANSIENT;
            return ErrorKind.API;
        }
        return error instanceof IOException ? ErrorKind.NETWORK : ErrorKind.CONFIGURATION;
    }

    private static ErrorKind classifyVault(Exception error) {
        if (error instanceof PayPalApiException) {
            PayPalApiException api = (PayPalApiException) error;
            String issue = api.issue == null ? "" : api.issue.trim();
            if (api.status == 403 && "NOT_AUTHORIZED".equalsIgnoreCase(issue)) {
                return ErrorKind.VAULT_UNAVAILABLE;
            }
        }
        return classify(error);
    }

    private static boolean reauthorizationIssue(String issue) {
        String normalized = issue == null ? "" : issue.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("PAYER_ACTION_REQUIRED")
                || normalized.contains("PAYMENT_SOURCE_DECLINED")
                || normalized.contains("PAYMENT_SOURCE_INFO_CANNOT_BE_VERIFIED")
                || normalized.contains("INSTRUMENT_DECLINED");
    }

    private static final class ReauthorizationRequiredException extends IOException {
        private ReauthorizationRequiredException(String message) { super(message); }
    }

    private static final class PayPalApiException extends IOException {
        private final int status;
        private final String issue;
        private final String context;
        private PayPalApiException(
                int status, String issue, String context, String message) {
            super(message);
            this.status = status;
            this.issue = issue == null ? "" : issue;
            this.context = context == null ? "" : context;
        }
    }

    private <T> void deliver(Callback<T> callback, Result<T> result) {
        main.post(() -> callback.complete(result));
    }

    public void close() {
        network.shutdownNow();
    }

    public interface Callback<T> { void complete(Result<T> result); }

    public enum ErrorKind {
        NETWORK, TRANSIENT, AUTHENTICATION, VAULT_UNAVAILABLE,
        REAUTHORIZATION_REQUIRED, CONFIGURATION, API
    }

    public static final class Result<T> {
        private final T value;
        private final String error;
        private final ErrorKind errorKind;
        private Result(T value, String error, ErrorKind errorKind) {
            this.value = value;
            this.error = error;
            this.errorKind = errorKind;
        }
        public static <T> Result<T> success(T value) {
            return new Result<>(value, "", null);
        }
        public static <T> Result<T> failure(String error, ErrorKind errorKind) {
            return new Result<>(null, error, errorKind);
        }
        public boolean isSuccess() { return value != null; }
        public T value() { return value; }
        public String error() { return error; }
        public ErrorKind errorKind() { return errorKind; }
    }

    public static final class Order {
        private final String id;
        private final String approvalUrl;
        private final String clientMetadataId;
        private final boolean vaultRequested;
        private Order(String id, String approvalUrl, String clientMetadataId,
                boolean vaultRequested) {
            this.id = id;
            this.approvalUrl = approvalUrl;
            this.clientMetadataId = clientMetadataId;
            this.vaultRequested = vaultRequested;
        }
        public String id() { return id; }
        public String approvalUrl() { return approvalUrl; }
        public String clientMetadataId() { return clientMetadataId; }
        public boolean vaultRequested() { return vaultRequested; }
    }

    public static final class OrderItem {
        private final String name;
        private final String description;
        private final int amountCents;

        public OrderItem(String name, String description, int amountCents) {
            this.name = name == null ? "" : name;
            this.description = description == null ? "" : description;
            this.amountCents = amountCents;
        }

        public String name() { return name; }
        public String description() { return description; }
        public int amountCents() { return amountCents; }
    }

    public static final class VaultSetup {
        private final String setupTokenId;
        private final String approvalUrl;
        private final String customerId;
        private final String clientMetadataId;

        private VaultSetup(String setupTokenId, String approvalUrl, String customerId,
                String clientMetadataId) {
            this.setupTokenId = setupTokenId;
            this.approvalUrl = approvalUrl;
            this.customerId = customerId;
            this.clientMetadataId = clientMetadataId;
        }

        public String setupTokenId() { return setupTokenId; }
        public String approvalUrl() { return approvalUrl; }
        public String customerId() { return customerId; }
        public String clientMetadataId() { return clientMetadataId; }
    }

    public static final class PaymentToken {
        private final String id;
        private final String customerId;
        private final String payerEmail;
        private final String payerAccountId;

        private PaymentToken(String id, String customerId, String payerEmail,
                String payerAccountId) {
            this.id = id;
            this.customerId = customerId;
            this.payerEmail = payerEmail;
            this.payerAccountId = payerAccountId;
        }

        public String id() { return id; }
        public String customerId() { return customerId; }
        public String payerEmail() { return payerEmail; }
        public String payerAccountId() { return payerAccountId; }
    }

    public static final class VaultSetupStatus {
        private final String status;
        private final String customerId;
        private final boolean confirmable;

        private VaultSetupStatus(String status, String customerId, boolean confirmable) {
            this.status = status == null ? "" : status;
            this.customerId = customerId == null ? "" : customerId;
            this.confirmable = confirmable;
        }

        public String status() { return status; }
        public String customerId() { return customerId; }
        public boolean isConfirmable() { return confirmable; }
    }

    public static final class Capture {
        private final String id;
        private final String vaultStatus;
        private final String vaultId;
        private final String customerId;
        private final String payerEmail;
        private final String payerAccountId;
        private Capture(String id, String vaultStatus, String vaultId, String customerId,
                String payerEmail, String payerAccountId) {
            this.id = id;
            this.vaultStatus = vaultStatus;
            this.vaultId = vaultId;
            this.customerId = customerId;
            this.payerEmail = payerEmail;
            this.payerAccountId = payerAccountId;
        }
        public String id() { return id; }
        public String vaultStatus() { return vaultStatus; }
        public String vaultId() { return vaultId; }
        public String customerId() { return customerId; }
        public String payerEmail() { return payerEmail; }
        public String payerAccountId() { return payerAccountId; }
    }
}
