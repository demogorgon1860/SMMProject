# Task 11 — Don't redirect after Place Order (client request)

## Context

(See `_CONTEXT.md`.)

## What's wrong

Customer feedback verbatim: "клиент попросил, чтобы не перекидывало после подтверждения заказа на страницу заказов, достаточно всплывающего подтверждения".

Current behavior in `frontend/src/pages/app/NewOrder.tsx`, `placeOrder` function (~line 145):

```ts
await orderAPI.create({ ... });
toast('Order placed.', 'success');
navigate('/orders');   // ← jarring redirect
```

After a successful order, the user lands on `/orders` and has to click `New order` again to place another. Friction for resellers placing dozens of orders per session.

## What to do

1. Remove the `navigate('/orders')` line entirely.
2. Make the toast informative — include the new order id and a link:
   ```ts
   const created = await orderAPI.create({ ... });  // returns OrderResponse with id
   toast(
     <span>
       Order #{created.id} placed.{' '}
       <Link to={`/orders/${created.id}`} className="underline">View it</Link>
     </span>,
     'success',
   );
   ```
   (If `useToast` doesn't accept JSX, extend it — the toast component should accept `string | ReactNode`.)
3. **Reset the form** so the user can immediately place another order without re-picking the service:
   - `setLink('')`
   - `setQty(<service min or 1000>)`
   - `setComments('')`
   - **Keep** `selectedId` selected — the user is probably ordering more of the same.
4. **Refresh balance**: the auth store balance just changed. Either:
   - Optimistically: `updateBalance(balance - charge)` from auth store
   - OR re-fetch: `balanceAPI.get().then(b => updateBalance(toNum(b.balance)))`. Pick re-fetch for accuracy.
5. **Backend response shape**: confirm `POST /v1/orders` returns the new `OrderResponse` (including `id`). If it returns `204 No Content` instead, change it to return the resource.
6. **Update `orderAPI.create` typing** in `frontend/src/services/api.ts` to `Promise<{ id: number; ... }>`.

## Verify

1. Pick a service, paste a link, enter quantity → click Place order.
2. Toast appears in the corner with order id and "View it" link.
3. URL stays at `/new-order`.
4. Form is cleared (link blank, qty reset to default).
5. Wallet chip in topbar shows the new balance.
6. Click the toast link → drawer opens at `/orders/{id}` showing the just-placed order.
7. Place another order — works without re-picking service.

## Production / best practices

Make sure everything is **maximum production-ready and follows best practices**. Toast should auto-dismiss after ~6s but the user can re-open the order via the dashboard. Don't introduce a lock that prevents a quick second order — the customer flagged this exact friction. Also handle the error case properly: on API failure, keep the form filled so the user can retry without re-typing.
