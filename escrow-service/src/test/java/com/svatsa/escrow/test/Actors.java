package com.svatsa.escrow.test;

import com.svatsa.escrow.common.Actor;
import com.svatsa.escrow.common.Role;

/** Actors for tests that call services directly. */
public final class Actors {

    private Actors() {
    }

    public static Actor client(long id) {
        return new Actor(id, Role.CLIENT);
    }

    public static Actor freelancer(long id) {
        return new Actor(id, Role.FREELANCER);
    }

    /** The bootstrap ADMIN (id 1, seeded by V11); matches {@link ApiClient#admin()}. */
    public static Actor admin() {
        return new Actor(ApiClient.BOOTSTRAP_ADMIN_ID, Role.ADMIN);
    }
}
