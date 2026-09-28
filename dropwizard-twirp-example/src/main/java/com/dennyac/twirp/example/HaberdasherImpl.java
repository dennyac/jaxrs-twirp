// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.example;

import com.dennyac.twirp.ErrorCode;
import com.dennyac.twirp.TwirpContext;
import com.dennyac.twirp.TwirpException;
import com.dennyac.twirp.example.haberdasher.Haberdasher;
import com.dennyac.twirp.example.haberdasher.HatStyle;
import com.dennyac.twirp.example.haberdasher.Hat;
import com.dennyac.twirp.example.haberdasher.Inventory;
import com.dennyac.twirp.example.haberdasher.InventoryRequest;
import com.dennyac.twirp.example.haberdasher.Size;
import com.dennyac.twirp.example.haberdasher.StockItem;
import com.dennyac.twirp.example.haberdasher.WhoAmIRequest;
import com.dennyac.twirp.example.haberdasher.WhoAmIResponse;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reference implementation of the Haberdasher Twirp service.
 *
 * <p>{@link #makeHat} picks a deterministic-ish hat for the requested size —
 * fedoras come big, bowlers come small — and shows the simplest possible RPC
 * shape (flat ints and strings). {@link #listInventory} returns a richer message
 * (enum + repeated nested message + map) so the example can demonstrate how those
 * proto features serialize under both the protobuf and JSON wire formats.
 *
 * <p>Both methods are normal Java methods that return a protobuf message; errors
 * are raised as {@link TwirpException}.
 */
public class HaberdasherImpl implements Haberdasher {

    private static final List<String> COLORS = List.of("red", "blue", "green", "black", "brown");

    // A fixed, deterministic catalogue so integration tests can assert exact
    // contents regardless of wire format.
    private static final List<StockItem> STOCK = List.of(
            stockItem(HatStyle.BOWLER, 7, "black"),
            stockItem(HatStyle.BOWLER, 8, "brown"),
            stockItem(HatStyle.FEDORA, 11, "grey"),
            stockItem(HatStyle.FEDORA, 12, "black"),
            stockItem(HatStyle.TOP_HAT, 14, "black"));

    @Override
    public Hat makeHat(Size request, TwirpContext context) throws TwirpException {
        if (request.getInches() <= 0) {
            throw TwirpException.builder(ErrorCode.INVALID_ARGUMENT)
                    .message("inches must be > 0")
                    .meta("argument", "inches")
                    .build();
        }
        String style = request.getInches() >= 10 ? "fedora" : "bowler";
        String color = COLORS.get(ThreadLocalRandom.current().nextInt(COLORS.size()));
        return Hat.newBuilder()
                .setInches(request.getInches())
                .setColor(color)
                .setStyleName(style)
                .build();
    }

    @Override
    public Inventory listInventory(InventoryRequest request, TwirpContext context) throws TwirpException {
        HatStyle filter = request.getStyle();
        List<StockItem> items = STOCK.stream()
                .filter(item -> filter == HatStyle.HAT_STYLE_UNSPECIFIED || item.getStyle() == filter)
                .toList();

        // map<string, int32>: how many hats we hold in each color.
        Map<String, Integer> countByColor = new TreeMap<>();
        for (StockItem item : items) {
            countByColor.merge(item.getColor(), 1, Integer::sum);
        }

        return Inventory.newBuilder()
                .addAllItems(items)
                .putAllCountByColor(countByColor)
                .build();
    }

    @Override
    public WhoAmIResponse whoAmI(WhoAmIRequest request, TwirpContext context) throws TwirpException {
        String subject = context.principal().map(Principal::getName).orElse("");
        return WhoAmIResponse.newBuilder()
                .setSubject(subject)
                .setAdmin(context.isUserInRole("admin"))
                .build();
    }

    private static StockItem stockItem(HatStyle style, int inches, String color) {
        return StockItem.newBuilder()
                .setStyle(style)
                .setInches(inches)
                .setColor(color)
                .build();
    }
}
