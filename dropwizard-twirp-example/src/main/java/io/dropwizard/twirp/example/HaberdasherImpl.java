package io.dropwizard.twirp.example;

import io.dropwizard.twirp.ErrorCode;
import io.dropwizard.twirp.TwirpException;
import io.dropwizard.twirp.example.haberdasher.Haberdasher;
import io.dropwizard.twirp.example.haberdasher.Hat;
import io.dropwizard.twirp.example.haberdasher.Size;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reference implementation of the Haberdasher Twirp service.
 *
 * <p>Picks a deterministic-ish hat for the requested size — fedoras come big,
 * bowlers come small. Demonstrates how the generated interface lets you write
 * a normal Java method that returns a protobuf message, with errors raised as
 * {@link TwirpException}.
 */
public class HaberdasherImpl implements Haberdasher {

    private static final List<String> COLORS = List.of("red", "blue", "green", "black", "brown");

    @Override
    public Hat makeHat(Size request) throws TwirpException {
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
}
