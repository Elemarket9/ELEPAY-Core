package com.elepay.operations;

import com.elepay.emtech.CoarseIpCities;
import com.elepay.emtech.ConsumerComplaints;
import com.elepay.emtech.EmtechDeviceIds;
import com.elepay.emtech.EmtechReporter;
import com.elepay.emtech.EmtechSettings;
import com.elepay.emtech.GhanaLocalities;
import com.elepay.emtech.PlaceSnapshots;
import com.elepay.emtech.RegulatoryFacts;
import com.elepay.emtech.ServerSidePlaces;
import com.elepay.emtech.ServerSidePlaces.Place;
import com.elepay.emtech.VirtualDevices;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads domain rows and queues an Emtech report in the same transaction.
 * Places are city, region and country derived on the server. A coarse IP is used
 * only as a fallback for web and app, then discarded. It is not stored.
 */
@Service
public class EmtechCapture {
    private final JdbcClient db;
    private final EmtechReporter reporter;
    private final PlaceSnapshots snapshots;
    private final VirtualDevices virtualDevices;
    private final ConsumerComplaints complaints;
    private final CoarseIpCities coarseIps;
    private final EmtechSettings settings;
    private final ObjectMapper json;

    public EmtechCapture(JdbcClient db, EmtechReporter reporter, PlaceSnapshots snapshots, VirtualDevices virtualDevices,
                         ConsumerComplaints complaints, CoarseIpCities coarseIps, EmtechSettings settings, ObjectMapper json) {
        this.db = db;
        this.reporter = reporter;
        this.snapshots = snapshots;
        this.virtualDevices = virtualDevices;
        this.complaints = complaints;
        this.coarseIps = coarseIps;
        this.settings = settings;
        this.json = json;
    }

    /** Resolves places at pay time. {@code ephemeralIp} is not written anywhere. */
    @Transactional
    public void rememberCheckout(UUID checkoutId, String ephemeralIp) {
        CheckoutRow checkout = checkout(checkoutId);
        if (checkout == null) return;
        boolean shop = posTerminal(checkout.reference()).isPresent();
        String channel = channel(checkout, shop);
        Place origin = resolveOrigin(channel, checkout, shop ? ephemeralIp : ipForChannel(channel, ephemeralIp));
        Place destination = resolveMerchantDestination(checkout);
        snapshots.save(checkoutId, origin, destination);
    }

    @Transactional
    public void paidCheckout(UUID checkoutId) {
        CheckoutRow checkout = checkout(checkoutId);
        if (checkout == null) return;
        Optional<TerminalRow> terminal = posTerminal(checkout.reference());
        String channel = channel(checkout, terminal.isPresent());
        Places places = placesFor(checkout, channel, null);
        RailRow rail = railForCheckout(checkoutId);
        long fee = checkout.customerFee();
        long originAmount = checkout.totalPaid() != null ? checkout.totalPaid() : Math.addExact(checkout.subtotal(), fee);
        long destinationAmount = Math.max(0, originAmount - fee);
        String instrument = checkout.paymentMethod() == null ? "wallet" : checkout.paymentMethod();
        CustomerRow customer = customer(checkout.customerId());
        String deviceId = deviceFor(channel, checkout.collectorId(), places.destination(), terminal.orElse(null));
        terminal.ifPresent(this::reportTerminal);
        reporter.transaction("checkout", checkout.reference(), new RegulatoryFacts.Transaction(
                checkout.reference(), "payment", "succeeded", channel, EmtechReporter.instant(checkout.paidAt()),
                deviceId, fee, "GHS",
                userId(customer, checkout.reference()),
                accountId(instrument, rail, customer, checkout.reference()),
                instrument, provider(instrument, rail),
                customer == null ? null : customer.regulatoryAccountType(),
                places.origin().city(), places.origin().region(), places.origin().country(), places.origin().source(),
                originAmount, "GHS",
                customer == null ? 0 : customer.tier(),
                customer == null ? null : customer.kycService(),
                customer == null ? null : customer.kycStatus(),
                customer == null ? null : customer.kycType(),
                customer == null ? null : customer.kycDetails(),
                null,
                ghanaCard(checkout.customerId()),
                faceApproved(checkout.customerId()),
                checkout.collectorId().toString(), "wallet", "ELEPAY", null,
                places.destination().city(), places.destination().region(), places.destination().country(),
                places.destination().source(),
                destinationAmount, "GHS",
                rail == null ? null : rail.reference(),
                rail == null ? null : rail.name(),
                rail == null ? null : rail.status(),
                rail == null ? null : rail.reason(),
                null));
    }

    @Transactional
    public void failedCheckout(UUID checkoutId, String reason) {
        CheckoutRow checkout = checkout(checkoutId);
        if (checkout == null) return;
        Attempt attempt = latestAttempt(checkoutId, "failed");
        if (attempt == null) return;
        Optional<TerminalRow> terminal = posTerminal(checkout.reference());
        String channel = channel(checkout, terminal.isPresent());
        Places places = placesFor(checkout, channel, null);
        CustomerRow customer = customer(checkout.customerId());
        long amount = attempt.amount() > 0 ? attempt.amount() : checkout.subtotal();
        long fee = attempt.fee() > 0 ? attempt.fee() : checkout.customerFee();
        String instrument = attempt.method() == null ? "wallet" : attempt.method();
        String deviceId = deviceFor(channel, checkout.collectorId(), places.destination(), terminal.orElse(null));
        String transactionId = checkout.reference() + "-" + attempt.id();
        reporter.transaction("checkout", transactionId, new RegulatoryFacts.Transaction(
                transactionId, "payment", failureStatus(reason), channel, EmtechReporter.instant(attempt.completedAt()),
                deviceId, fee, "GHS",
                userId(customer, checkout.reference()),
                accountId(instrument, attempt.rail(), customer, checkout.reference()),
                instrument, provider(instrument, attempt.rail()),
                customer == null ? null : customer.regulatoryAccountType(),
                places.origin().city(), places.origin().region(), places.origin().country(), places.origin().source(),
                amount, "GHS",
                customer == null ? 0 : customer.tier(),
                customer == null ? null : customer.kycService(),
                customer == null ? null : customer.kycStatus(),
                customer == null ? null : customer.kycType(),
                customer == null ? null : customer.kycDetails(),
                null, ghanaCard(checkout.customerId()), faceApproved(checkout.customerId()),
                checkout.collectorId().toString(), "wallet", "ELEPAY", null,
                places.destination().city(), places.destination().region(), places.destination().country(),
                places.destination().source(),
                Math.max(0, amount - fee), "GHS",
                attempt.rail() == null ? null : attempt.rail().reference(),
                attempt.rail() == null ? null : attempt.rail().name(),
                "failed", reason, reason));
    }

    @Transactional
    public void refund(UUID refundId) {
        RefundRow refund = db.sql("""
                SELECT r.id, r.reference, r.amount, r.status, r.reason, r.created_at, r.checkout_id
                FROM refund r WHERE r.id = :id""")
                .param("id", refundId)
                .query((rs, i) -> new RefundRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getObject(7, UUID.class)))
                .optional().orElse(null);
        if (refund == null || "pending".equals(refund.status())) return;
        CheckoutRow checkout = checkout(refund.checkoutId());
        if (checkout == null) return;
        CustomerRow customer = customer(checkout.customerId());
        Place merchantPlace = resolveMerchantDestination(checkout);
        Place customerPlace = customer == null ? Place.none()
                : Place.of(customer.city(), customer.region(), customer.country(),
                customer.localitySource() == null ? "PROFILE" : customer.localitySource());
        String channel = channel(checkout, posTerminal(checkout.reference()).isPresent());
        String deviceId = deviceFor(channel, checkout.collectorId(), merchantPlace, posTerminal(checkout.reference()).orElse(null));
        String elepayStatus = "failed".equals(refund.status()) ? "failed" : "succeeded";
        reporter.transaction("refund", refund.reference(), new RegulatoryFacts.Transaction(
                refund.reference(), "refund", elepayStatus, channel, EmtechReporter.instant(refund.createdAt()),
                deviceId, 0, "GHS",
                checkout.collectorId().toString(), checkout.collectorId().toString(), "wallet", "ELEPAY", null,
                merchantPlace.city(), merchantPlace.region(), merchantPlace.country(), merchantPlace.source(),
                refund.amount(), "GHS", 0, null, null, null, null, null, null, false,
                userId(customer, checkout.reference()),
                checkout.paymentMethod() == null ? "wallet" : checkout.paymentMethod(),
                "ELEPAY", customer == null ? null : customer.regulatoryAccountType(),
                customerPlace.city(), customerPlace.region(), customerPlace.country(), customerPlace.source(),
                refund.amount(), "GHS", null, null, null, null, refund.reason()));
    }

    /** City-level origin for a customer request. The IP argument is not stored. */
    public Place customerOrigin(UUID customerId, String channel, String ephemeralIp) {
        CustomerRow who = customer(customerId);
        return ServerSidePlaces.origin(channel, null, profile(who), coarse(channel, ephemeralIp), mno(who), null);
    }

    @Transactional
    public void walletTransfer(UUID outgoingId, String ephemeralIp) {
        WalletRow out = wallet(outgoingId);
        if (out == null) return;
        WalletRow incoming = out.relatedId() == null ? null : wallet(out.relatedId());
        CustomerRow sender = customer(out.customerId());
        CustomerRow recipient = incoming == null ? null : customer(incoming.customerId());
        String channel = out.channel() == null ? "WEB" : out.channel();
        Place origin = out.originCity() != null
                ? Place.of(out.originCity(), out.originRegion(), out.originCountry(), out.originSource())
                : ServerSidePlaces.origin(channel, null, profile(sender), coarse(channel, ephemeralIp), mno(sender), null);
        Place destination = ServerSidePlaces.recipient(profile(recipient), mno(recipient));
        String deviceId = platformDevice(channel);
        reporter.transaction("wallet", out.reference(), new RegulatoryFacts.Transaction(
                out.reference(), "wallet_transfer", out.status(), channel, EmtechReporter.instant(out.createdAt()),
                deviceId, 0, "GHS",
                out.customerId().toString(), out.customerId().toString(), "wallet", "ELEPAY",
                sender == null ? null : sender.regulatoryAccountType(),
                origin.city(), origin.region(), origin.country(), origin.source(),
                out.amount(), "GHS",
                sender == null ? 0 : sender.tier(),
                sender == null ? null : sender.kycService(),
                sender == null ? null : sender.kycStatus(),
                sender == null ? null : sender.kycType(),
                sender == null ? null : sender.kycDetails(),
                null, ghanaCard(out.customerId()), faceApproved(out.customerId()),
                recipient == null ? out.reference() : recipient.id().toString(),
                "wallet", "ELEPAY", recipient == null ? null : recipient.regulatoryAccountType(),
                destination.city(), destination.region(), destination.country(), destination.source(),
                incoming == null ? out.amount() : incoming.amount(), "GHS",
                null, null, null, null, "ELEPAY wallet transfer"));
    }

    @Transactional
    public void walletTopup(UUID operationId, String ephemeralIp) {
        WalletRow op = wallet(operationId);
        if (op == null || "pending".equals(op.status())) return;
        CustomerRow customer = customer(op.customerId());
        RailRow rail = op.railTx() == null ? null : rail(op.railTx());
        String channel = op.channel() == null ? "WEB" : op.channel();
        String instrument = rail != null && rail.msisdn() != null ? "momo" : "card";
        Place origin = op.originCity() != null
                ? Place.of(op.originCity(), op.originRegion(), op.originCountry(), op.originSource())
                : ServerSidePlaces.origin(channel, null, profile(customer), coarse(channel, ephemeralIp), mno(customer), null);
        String deviceId = platformDevice(channel);
        long fee = rail == null || rail.fee() == null ? 0 : rail.fee();
        reporter.transaction("wallet", op.reference(), new RegulatoryFacts.Transaction(
                op.reference(), "deposit", op.status(), channel, EmtechReporter.instant(op.createdAt()),
                deviceId, fee, "GHS",
                op.customerId().toString(),
                rail != null && rail.msisdn() != null ? rail.msisdn() : op.customerId().toString(),
                instrument, provider(instrument, rail), null,
                origin.city(), origin.region(), origin.country(), origin.source(),
                op.amount(), "GHS",
                customer == null ? 0 : customer.tier(),
                customer == null ? null : customer.kycService(),
                customer == null ? null : customer.kycStatus(),
                customer == null ? null : customer.kycType(),
                customer == null ? null : customer.kycDetails(),
                null, ghanaCard(op.customerId()), faceApproved(op.customerId()),
                op.customerId().toString(), "wallet", "ELEPAY",
                customer == null ? null : customer.regulatoryAccountType(),
                origin.city(), origin.region(), origin.country(), origin.source(),
                Math.max(0, op.amount() - fee), "GHS",
                rail == null ? null : rail.reference(), rail == null ? null : rail.name(),
                rail == null ? null : rail.status(), rail == null ? null : rail.reason(), null));
    }

    @Transactional
    public void payout(UUID payoutId) {
        PayoutRow payout = db.sql("""
                SELECT id, reference, owner_type, owner_id, amount, status, destination::text, rail,
                       failure_reason, completed_at
                FROM payout WHERE id = :id""")
                .param("id", payoutId)
                .query((rs, i) -> new PayoutRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getLong(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getObject(10, OffsetDateTime.class)))
                .optional().orElse(null);
        if (payout == null) return;
        JsonNode destination = json.readTree(payout.destinationJson());
        String type = destination.path("type").asString("bank");
        String network = destination.path("network").asString(payout.rail());
        String account = destination.path("accountNumber").asString(destination.path("account").asString(payout.ownerId().toString()));
        Place ownerPlace = "customer".equals(payout.ownerType())
                ? Optional.ofNullable(profile(customer(payout.ownerId()))).orElse(Place.none())
                : merchantPlace(payout.ownerId());
        Place destPlace = settlementPlace(payout.ownerType(), payout.ownerId()).orElse(ownerPlace);
        String channel = "WEB";
        reporter.transaction("payout", payout.reference(), new RegulatoryFacts.Transaction(
                payout.reference(), "withdrawal", payout.status(), channel, EmtechReporter.instant(payout.completedAt()),
                platformDevice(channel), 0, "GHS",
                payout.ownerId().toString(), payout.ownerId().toString(), "wallet", "ELEPAY", null,
                ownerPlace.city(), ownerPlace.region(), ownerPlace.country(), ownerPlace.source(),
                payout.amount(), "GHS",
                "customer".equals(payout.ownerType()) && customer(payout.ownerId()) != null ? customer(payout.ownerId()).tier() : 0,
                null, null, null, null, null,
                "customer".equals(payout.ownerType()) ? ghanaCard(payout.ownerId()) : null,
                "customer".equals(payout.ownerType()) && faceApproved(payout.ownerId()),
                account, type, network, null,
                destPlace.city(), destPlace.region(), destPlace.country(), destPlace.source(),
                payout.amount(), "GHS",
                null, payout.rail(), payout.status(), payout.reason(), payout.reason()));
    }

    @Transactional
    public void disputeOpened(UUID disputeId) {
        dispute(disputeId, false);
    }

    @Transactional
    public void disputeResolved(UUID disputeId) {
        dispute(disputeId, true);
    }

    private void dispute(UUID disputeId, boolean resolved) {
        var row = db.sql("""
                SELECT d.id, d.checkout_id, d.reason, d.status, d.resolution_note, d.opened_by, d.created_at, d.resolved_at,
                       c.customer_id
                FROM dispute d JOIN checkout c ON c.id = d.checkout_id
                WHERE d.id = :id""")
                .param("id", disputeId)
                .query((rs, i) -> new Object[]{
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class),
                        rs.getObject(8, OffsetDateTime.class), rs.getObject(9, UUID.class)})
                .optional().orElse(null);
        if (row == null) return;
        UUID customerId = (UUID) row[8];
        String userId = customerId != null ? customerId.toString() : String.valueOf(row[5]);
        String status = resolved ? "RESOLVED" : "OPENED";
        String resolution = resolved ? row[4] + " (" + row[3] + ")" : null;
        complaints.syncDispute((UUID) row[0], customerId, userId, (String) row[2], status, resolution,
                resolved ? (OffsetDateTime) row[7] : (OffsetDateTime) row[6], (String) row[5]);
    }

    private Places placesFor(CheckoutRow checkout, String channel, String ephemeralIp) {
        Optional<PlaceSnapshots.Snapshot> saved = snapshots.find(checkout.id());
        if (saved.isPresent() && saved.get().origin().usable()) {
            return new Places(saved.get().origin(), saved.get().destination());
        }
        return new Places(resolveOrigin(channel, checkout, ipForChannel(channel, ephemeralIp)),
                resolveMerchantDestination(checkout));
    }

    private Place resolveOrigin(String channel, CheckoutRow checkout, String ephemeralIp) {
        TerminalRow terminal = posTerminal(checkout.reference()).orElse(null);
        CustomerRow customer = customer(checkout.customerId());
        return ServerSidePlaces.origin(channel,
                terminal == null ? null : Place.of(terminal.city(), terminal.region(), terminal.country(), "TERMINAL"),
                profile(customer), coarse(channel, ephemeralIp), mno(customer), branchPlace(checkout.branchId()));
    }

    private Place resolveMerchantDestination(CheckoutRow checkout) {
        TerminalRow terminal = posTerminal(checkout.reference()).orElse(null);
        return ServerSidePlaces.merchantDestination(
                branchPlace(checkout.branchId()),
                merchantPlace(checkout.collectorId()),
                settlementPlace("merchant", checkout.collectorId()).orElse(null),
                terminal == null ? null : Place.of(terminal.city(), terminal.region(), terminal.country(), "TERMINAL"));
    }

    private String deviceFor(String channel, UUID merchantId, Place where, TerminalRow terminal) {
        if (ServerSidePlaces.isShopChannel(channel) && terminal != null) return EmtechDeviceIds.terminal(terminal.id());
        String city = where != null && where.city() != null ? where.city() : settings.platformCity();
        String region = where != null && where.region() != null ? where.region()
                : GhanaLocalities.region(city).orElse(settings.platformRegion());
        String country = where != null && where.country() != null ? where.country() : settings.platformCountry();
        String[] point = shopPoint(merchantId, city).orElseGet(() -> GhanaLocalities.deviceReferencePoint(city).orElse(null));
        if (point == null) return EmtechDeviceIds.merchantChannel(merchantId, channel);
        return virtualDevices.ensureMerchant(merchantId, channel, city, region, country, point[0], point[1],
                EmtechDeviceIds.connectionFor(channel));
    }

    private String platformDevice(String channel) {
        return virtualDevices.ensurePlatform(channel, settings.platformCity(), settings.platformRegion(),
                settings.platformCountry(), settings.platformLatitude().toPlainString(),
                settings.platformLongitude().toPlainString());
    }

    private Optional<String[]> shopPoint(UUID merchantId, String city) {
        return db.sql("""
                SELECT latitude, longitude FROM pos_terminal
                WHERE merchant_id = :m AND city IS NOT NULL AND lower(city) = lower(:city) AND latitude IS NOT NULL
                ORDER BY created_at LIMIT 1""")
                .param("m", merchantId).param("city", city)
                .query((rs, i) -> new String[]{plain(rs.getBigDecimal(1)), plain(rs.getBigDecimal(2))})
                .optional();
    }

    private void reportTerminal(TerminalRow terminal) {
        if (terminal.latitude() == null || terminal.city() == null || terminal.connectionType() == null) return;
        Place place = Place.of(terminal.city(), terminal.region(), terminal.country(), "TERMINAL");
        reporter.device(new RegulatoryFacts.Device(
                terminal.id().toString(), terminal.deviceType() == null ? "POS" : terminal.deviceType(),
                terminal.name(), place.city(), place.region(), place.country(), terminal.address(), terminal.location(),
                plain(terminal.latitude()), plain(terminal.longitude()), terminal.model(), terminal.manufacturer(),
                terminal.description(), EmtechReporter.instant(terminal.inservice()), terminal.connectionType(),
                terminal.manufactureYear()));
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String ipForChannel(String channel, String ip) {
        if (ip == null || ip.isBlank()) return null;
        if ("WEB".equals(channel) || "MOBILE_APP".equals(channel)) return ip;
        return null;
    }

    private Place coarse(String channel, String ip) {
        String allowed = ipForChannel(channel, ip);
        if (allowed == null) return null;
        return coarseIps.lookup(allowed).orElse(null);
    }

    private static Place profile(CustomerRow customer) {
        if (customer == null || customer.city() == null || customer.city().isBlank()) return null;
        String source = "GHANA_CARD".equals(customer.localitySource()) ? "GHANA_CARD" : "PROFILE";
        return Place.of(customer.city(), customer.region(), customer.country(), source);
    }

    private static Place mno(CustomerRow customer) {
        if (customer == null || customer.mnoCity() == null || customer.mnoCity().isBlank()) return null;
        return Place.of(customer.mnoCity(), customer.mnoRegion(), customer.country(), "MNO");
    }

    private Place branchPlace(UUID branchId) {
        if (branchId == null) return null;
        return db.sql("SELECT city, region, country FROM merchant_branch WHERE id = :id")
                .param("id", branchId)
                .query((rs, i) -> Place.of(rs.getString(1), rs.getString(2), rs.getString(3), "BRANCH"))
                .optional().orElse(null);
    }

    private Place merchantPlace(UUID merchantId) {
        return db.sql("SELECT city, region, country FROM merchant WHERE id = :id")
                .param("id", merchantId)
                .query((rs, i) -> Place.of(rs.getString(1), rs.getString(2), rs.getString(3), "PROFILE"))
                .optional().orElse(Place.none());
    }

    private Optional<Place> settlementPlace(String ownerType, UUID ownerId) {
        return db.sql("""
                SELECT city, region, country FROM settlement_destination
                WHERE owner_type = :t AND owner_id = :id AND status = 'active' AND city IS NOT NULL
                ORDER BY created_at DESC LIMIT 1""")
                .param("t", ownerType).param("id", ownerId)
                .query((rs, i) -> Place.of(rs.getString(1), rs.getString(2), rs.getString(3), "SETTLEMENT"))
                .optional();
    }

    private CheckoutRow checkout(UUID id) {
        return db.sql("""
                SELECT id, reference, collector_id, branch_id, customer_id, subtotal, customer_fee, total_paid,
                       payment_method, paid_at, channel, created_by
                FROM checkout WHERE id = :id""")
                .param("id", id)
                .query((rs, i) -> new CheckoutRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getLong(6), rs.getLong(7),
                        (Long) rs.getObject(8), rs.getString(9), rs.getObject(10, OffsetDateTime.class),
                        rs.getString(11), rs.getString(12)))
                .optional().orElse(null);
    }

    private CustomerRow customer(UUID id) {
        if (id == null) return null;
        return db.sql("""
                SELECT id, city, region, country, locality_source, mno_city, mno_region, kyc_tier,
                       regulatory_account_type, kyc_service, kyc_type, kyc_status, kyc_type_details
                FROM customer WHERE id = :id""")
                .param("id", id)
                .query((rs, i) -> new CustomerRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13)))
                .optional().orElse(null);
    }

    private Optional<TerminalRow> posTerminal(String reference) {
        return db.sql("""
                SELECT t.id, t.city, t.region, t.country, t.connection_type, t.latitude, t.longitude,
                       t.device_type, t.device_name, t.model, t.manufacturer, t.device_description,
                       t.address_line, t.location_description, t.manufacture_year,
                       coalesce(t.inservice_at, t.paired_at, t.created_at)
                FROM pos_checkout_link l
                JOIN pos_order o ON o.id = l.order_id
                JOIN pos_terminal t ON t.id = o.terminal_id
                WHERE l.checkout_ref = :ref
                LIMIT 1""")
                .param("ref", reference)
                .query((rs, i) -> new TerminalRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13),
                        rs.getString(14), (Integer) rs.getObject(15), rs.getObject(16, OffsetDateTime.class)))
                .optional();
    }

    private RailRow railForCheckout(UUID checkoutId) {
        return db.sql("""
                SELECT r.rail, r.rail_reference, r.status, r.failure_reason, r.msisdn, r.rail_fee
                FROM payment_attempt a JOIN rail_transaction r ON r.id = a.rail_tx
                WHERE a.checkout_id = :id AND a.status = 'succeeded'
                ORDER BY a.created_at DESC LIMIT 1""")
                .param("id", checkoutId)
                .query((rs, i) -> new RailRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), (Long) rs.getObject(6)))
                .optional().orElse(null);
    }

    private RailRow rail(UUID railTx) {
        return db.sql("""
                SELECT rail, rail_reference, status, failure_reason, msisdn, rail_fee
                FROM rail_transaction WHERE id = :id""")
                .param("id", railTx)
                .query((rs, i) -> new RailRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), (Long) rs.getObject(6)))
                .optional().orElse(null);
    }

    private Attempt latestAttempt(UUID checkoutId, String status) {
        return db.sql("""
                SELECT a.id, a.method, r.amount, r.rail_fee, r.completed_at, r.rail, r.rail_reference, r.status,
                       r.failure_reason, r.msisdn
                FROM payment_attempt a LEFT JOIN rail_transaction r ON r.id = a.rail_tx
                WHERE a.checkout_id = :id AND a.status = :status
                ORDER BY a.created_at DESC LIMIT 1""")
                .param("id", checkoutId).param("status", status)
                .query((rs, i) -> new Attempt(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                        rs.getLong(4), rs.getObject(5, OffsetDateTime.class),
                        rs.getString(6) == null ? null : new RailRow(rs.getString(6), rs.getString(7), rs.getString(8),
                                rs.getString(9), rs.getString(10), rs.getLong(4))))
                .optional().orElse(null);
    }

    private WalletRow wallet(UUID id) {
        return db.sql("""
                SELECT id, reference, customer_id, amount, status, channel, created_at, related_id, rail_tx,
                       origin_city, origin_region, origin_country, origin_source
                FROM wallet_operation WHERE id = :id""")
                .param("id", id)
                .query((rs, i) -> new WalletRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getLong(4), rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class),
                        rs.getObject(8, UUID.class), rs.getObject(9, UUID.class),
                        rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13)))
                .optional().orElse(null);
    }

    private String ghanaCard(UUID customerId) {
        if (customerId == null) return null;
        return db.sql("""
                SELECT status FROM ghana_card_verification WHERE customer_id = :id
                ORDER BY verified_at DESC LIMIT 1""")
                .param("id", customerId).query(String.class).optional().orElse(null);
    }

    private boolean faceApproved(UUID customerId) {
        if (customerId == null) return false;
        return db.sql("""
                SELECT status = 'APPROVED' AND is_live FROM biometric_face_verification
                WHERE customer_id = :id ORDER BY created_at DESC LIMIT 1""")
                .param("id", customerId).query(Boolean.class).optional().orElse(false);
    }

    private static String channel(CheckoutRow checkout, boolean shop) {
        if (shop) return "POS";
        if (checkout.channel() != null && !checkout.channel().isBlank()) return checkout.channel();
        if (checkout.createdBy() != null && checkout.createdBy().startsWith("ussd:")) return "MOBILE_USSD";
        return "WEB";
    }

    private static String userId(CustomerRow customer, String reference) {
        return customer == null ? "guest-" + reference : customer.id().toString();
    }

    private static String accountId(String instrument, RailRow rail, CustomerRow customer, String reference) {
        if ("momo".equals(instrument) && rail != null && rail.msisdn() != null) return rail.msisdn();
        if (customer != null) return customer.id().toString();
        return "guest-" + reference;
    }

    private static String provider(String instrument, RailRow rail) {
        if ("momo".equals(instrument) && rail != null && rail.name() != null) return rail.name();
        if ("card".equals(instrument)) return "CARD";
        return "ELEPAY";
    }

    private static String failureStatus(String reason) {
        String value = reason == null ? "" : reason.toLowerCase();
        if (value.contains("suspect") || value.contains("fraud") || value.contains("sanction")) return "suspect";
        if (value.contains("reject") || value.contains("insufficient")) return "rejected";
        return "failed";
    }

    private record Places(Place origin, Place destination) {}

    private record CheckoutRow(UUID id, String reference, UUID collectorId, UUID branchId, UUID customerId,
                               long subtotal, long customerFee, Long totalPaid, String paymentMethod,
                               OffsetDateTime paidAt, String channel, String createdBy) {}

    private record CustomerRow(UUID id, String city, String region, String country, String localitySource,
                               String mnoCity, String mnoRegion, int tier, String regulatoryAccountType,
                               String kycService, String kycType, String kycStatus, String kycDetails) {}

    private record TerminalRow(UUID id, String city, String region, String country, String connectionType,
                               BigDecimal latitude, BigDecimal longitude, String deviceType, String name, String model,
                               String manufacturer, String description, String address, String location,
                               Integer manufactureYear, OffsetDateTime inservice) {}

    private record RailRow(String name, String reference, String status, String reason, String msisdn, Long fee) {}

    private record Attempt(UUID id, String method, long amount, long fee, OffsetDateTime completedAt, RailRow rail) {}

    private record RefundRow(UUID id, String reference, long amount, String status, String reason,
                             OffsetDateTime createdAt, UUID checkoutId) {}

    private record WalletRow(UUID id, String reference, UUID customerId, long amount, String status, String channel,
                             OffsetDateTime createdAt, UUID relatedId, UUID railTx,
                             String originCity, String originRegion, String originCountry, String originSource) {}

    private record PayoutRow(UUID id, String reference, String ownerType, UUID ownerId, long amount, String status,
                             String destinationJson, String rail, String reason, OffsetDateTime completedAt) {}
}
