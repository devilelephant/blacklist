package com.devilelephant;

import software.amazon.awscdk.core.App;

public final class CdkApp {
    public static void main(final String[] args) {
        App app = new App();

        new CdkStack(app, "BlockList");

        app.synth();
    }
}
