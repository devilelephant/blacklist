package com.devilelephant;

import software.amazon.awscdk.core.App;

public final class InfrastructureApp {
    public static void main(final String[] args) {
        App app = new App();

        new InfrastructureStack(app, "BlacklistStack");

        app.synth();
    }
}
