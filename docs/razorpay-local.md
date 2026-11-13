# Razorpay Local Setup for Test-Mode Deposits

This guide explains how to set up Razorpay test-mode payments locally with webhook support.

## Step 1: Get Test Keys from Razorpay

1. Sign up for a free Razorpay account at https://razorpay.com
2. Go to **Settings → API Keys** (Test Mode tab)
3. Copy your **Key ID** and **Key Secret**
4. Go to **Webhooks** section and copy your **Webhook Secret** for the test mode

## Step 2: Configure Environment Variables

Set these environment variables before running the application:

```bash
export RAZORPAY_KEY_ID="your_test_key_id_here"
export RAZORPAY_KEY_SECRET="your_test_key_secret_here"
export RAZORPAY_WEBHOOK_SECRET="your_webhook_secret_here"
```

## Step 3: Set Up Public HTTPS Tunnel

Razorpay webhooks require a public HTTPS URL on port 443. Use [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-applications/) (free):

1. Install Cloudflare CLI:
   ```bash
   brew install cloudflare/cloudflare/cloudflared  # macOS
   # or download from https://developers.cloudflare.com/cloudflare-one/connections/connect-applications/install-and-setup/installation/
   ```

2. Start the tunnel pointing to your local app:
   ```bash
   cloudflared tunnel --url http://localhost:8080
   ```
   
   This will output something like:
   ```
   https://abc123-def456.trycloudflare.com
   ```

3. Note the public URL from the output above

## Step 4: Register Webhook in Razorpay Dashboard

1. Go to **Razorpay Dashboard → Settings → Webhooks** (Test Mode)
2. Click **Add New Webhook**
3. Set **Webhook URL** to: `https://your-tunnel-url/webhooks/razorpay`
   - Replace `your-tunnel-url` with the URL from Step 3
4. Select these **Events**:
   - `payment.captured`
   - `order.paid`
   - `payment.failed`
5. Click **Create Webhook**

## Step 5: Start the Application

```bash
# From the project root
docker compose up -d  # Start Postgres, Kafka, Redis, etc.
./mvnw spring-boot:run -pl escrow-service
```

The app should now be running at `http://localhost:8080`

## Step 6: Test a Deposit

1. Open `http://localhost:8080/deposit.html`
2. Enter an amount (e.g., 100 for ₹100)
3. Click "Proceed to Payment"
4. Use a **Razorpay test card**:
   - Card Number: `4111 1111 1111 1111`
   - Expiry: Any future date (e.g., 12/25)
   - CVV: Any 3 digits (e.g., 123)
   - Name: Any name
5. Click "Pay ₹100"
6. After successful payment, wait a few seconds for the webhook to process
7. You should see "Wallet credited successfully!" message
8. Check the database:
   ```sql
   SELECT * FROM deposits WHERE status = 'CAPTURED';
   SELECT * FROM accounts WHERE code LIKE 'CLIENT_WALLET:%';
   ```

## Test Cards

Razorpay provides these test cards for various scenarios:

- **Success**: `4111 1111 1111 1111`
- **Failure**: `4444 3333 2222 1111`
- **3D Secure**: Use `5555 5555 5555 4444`

See https://razorpay.com/docs/payments/payments-api/test-card-details/ for more options.

## Troubleshooting

### Webhook Not Triggered

1. Check tunnel is running: `cloudflared tunnel --url http://localhost:8080`
2. Verify webhook URL in Razorpay dashboard includes `/webhooks/razorpay`
3. Check application logs for webhook errors:
   ```bash
   docker logs escrow-service | grep webhook
   ```
4. Check `webhook_events` table:
   ```sql
   SELECT * FROM webhook_events ORDER BY received_at DESC LIMIT 5;
   ```

### Signature Verification Failed

1. Verify `RAZORPAY_WEBHOOK_SECRET` is exactly as shown in Razorpay dashboard
2. Check application logs for signature verification errors:
   ```bash
   ./mvnw logs escrow-service | grep signature
   ```

### Wallet Not Credited

1. Check `deposits` table for the deposit status:
   ```sql
   SELECT * FROM deposits ORDER BY created_at DESC LIMIT 1;
   ```
2. If status is `CREATED`, webhook hasn't been processed yet (wait longer or check logs)
3. If status is `AMOUNT_MISMATCH`, the amount in webhook didn't match the order
4. Check `webhook_events` table for processing status:
   ```sql
   SELECT * FROM webhook_events WHERE event_type = 'payment.captured' ORDER BY received_at DESC LIMIT 1;
   ```

## Production Considerations

- **Never commit test keys** to version control. Always use environment variables.
- **Use production keys** when deploying to production.
- **Monitor webhooks** - set up alerts for failed webhook processing.
- **Keep webhook secret secure** - treat it like a password.
- **Verify signatures** always - never trust webhook data without verification.
- **Use HTTPS** in production (not just Cloudflare tunnel).
- **Handle retries** - Razorpay retries webhooks if you don't return 200 OK.

## References

- [Razorpay Documentation](https://razorpay.com/docs/)
- [Razorpay Webhooks](https://razorpay.com/docs/webhooks/)
- [Razorpay Test Mode](https://razorpay.com/docs/payments/payment-gateway/test-mode/)
- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-applications/)
