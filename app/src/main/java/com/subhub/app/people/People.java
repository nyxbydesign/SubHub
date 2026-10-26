package com.subhub.app.people;

import android.content.Context;

/** App integration point for a transport-owned remote repository. */
public final class People {
    private static volatile PersonRepository remote;
    private static volatile java.util.function.BooleanSupplier remoteAuthority = () -> false;
    private static volatile ConnectionUi connectionUi;
    private static volatile java.util.function.UnaryOperator<PersonSnapshot> localProjection=java.util.function.UnaryOperator.identity();
    private static volatile com.subhub.app.security.KeyholderProvider keyholder=new com.subhub.app.security.KeyholderProvider() {
        public State state(){return State.UNAVAILABLE;}
        public String assignedDomName(){return "";}
        public com.subhub.app.security.ControllerAuthenticator.Result verifyCode(String code){return com.subhub.app.security.ControllerAuthenticator.Result.UNAVAILABLE;}
    };
    public static com.subhub.app.security.KeyholderProvider keyholder(){return keyholder;}
    public static void installKeyholder(com.subhub.app.security.KeyholderProvider provider){keyholder=java.util.Objects.requireNonNull(provider);}
    private People() {}

    public static PersonRepository repository(Context context) {
        LocalPersonRepository local=new LocalPersonRepository(context.getApplicationContext());
        return new PersonDirectory(new PersonRepository() {
            @Override public java.util.List<PersonId> people(){return local.people();}
            @Override public PersonSnapshot load(PersonId id){return localProjection.apply(local.load(id));}
        }, remote);
    }

    public static void installRemoteRepository(PersonRepository repository) { remote = repository; }
    public static void installConnectionUi(ConnectionUi ui) { connectionUi = ui; }
    public static ConnectionUi connectionUi() { return connectionUi; }
    public static void installLocalProjection(java.util.function.UnaryOperator<PersonSnapshot> projection){localProjection=java.util.Objects.requireNonNull(projection);}

    /** The pairing owner supplies durable authority; UI mode never grants remote permission. */
    public static void installRemoteAuthority(java.util.function.BooleanSupplier authority) {
        remoteAuthority = java.util.Objects.requireNonNull(authority);
    }

    public static boolean isLocalConfigurationAllowed() {
        try { return !remoteAuthority.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }
}
