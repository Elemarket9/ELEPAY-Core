package com.elepay.merchanttools;

import com.elepay.merchanttools.restaurant.KitchenDeviceService;
import com.elepay.merchanttools.restaurant.RestaurantPushState;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Narrow public read-only module API for restaurant push authorization and state. */
@Service
public class RestaurantNotificationAccess {
    private final KitchenDeviceService kitchen;
    private final RestaurantPushState state;

    public RestaurantNotificationAccess(KitchenDeviceService kitchen, RestaurantPushState state) {
        this.kitchen = kitchen;
        this.state = state;
    }

    public boolean permitsNotificationStation(UUID merchant, UUID credential, UUID station) {
        return kitchen.permitsNotificationStation(merchant, credential, station);
    }

    public boolean kitchenOrderPending(UUID merchant, UUID ticket, UUID station) {
        return state.kitchenOrderPending(merchant, ticket, station);
    }

    public boolean readyForWaiter(UUID merchant, UUID ticket, String subject, UUID branch) {
        return state.readyForWaiter(merchant, ticket, subject, branch);
    }

    public boolean billOutstanding(UUID merchant, UUID order, UUID branch) {
        return state.billOutstanding(merchant, order, branch);
    }
}
