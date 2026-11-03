package com.subhub.app.remote;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.HashMap;
import java.util.Map;
import javax.security.auth.x500.X500Principal;

/** Feature-private persistence receipts. They never authenticate a person or grant application access. */
final class LinkingReceiptStore {
    static final String INIT="init", EVER="ever", CURRENT="current";
    private final String prefix;
    private final String database;
    static final class Receipt {
        final String epoch, digest; final long revision;
        Receipt(String epoch,long revision,String digest) {
            if(epoch==null||!epoch.matches("[0-9a-f]{32}")||revision<0||digest==null||!digest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid persistence receipt");
            this.epoch=epoch;this.revision=revision;this.digest=digest;
        }
        boolean same(Receipt other){return other!=null&&revision==other.revision&&epoch.equals(other.epoch)&&digest.equals(other.digest);}
    }
    LinkingReceiptStore(String databaseIdentity) {
        database=LocalRelationshipAuthority.digest(databaseIdentity);
        prefix="subhub.linking.v1."+database+".";
    }
    private static KeyStore keys()throws GeneralSecurityException {
        try{KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);return keys;}
        catch(java.io.IOException failure){throw new GeneralSecurityException("Persistence key store unavailable",failure);}
    }
    private String alias(String kind){
        if(!INIT.equals(kind)&&!EVER.equals(kind)&&!CURRENT.equals(kind))throw new IllegalArgumentException("Receipt kind");
        return prefix+kind;
    }
    Receipt read(String kind)throws GeneralSecurityException {
        KeyStore keys=keys();String alias=alias(kind);if(!keys.containsAlias(alias))return null;
        if(!(keys.getCertificate(alias) instanceof X509Certificate)||!(keys.getKey(alias,null) instanceof PrivateKey))
            throw new GeneralSecurityException("Incomplete persistence receipt");
        X509Certificate certificate=(X509Certificate)keys.getCertificate(alias);
        if(!"EC".equals(certificate.getPublicKey().getAlgorithm()))throw new GeneralSecurityException("Wrong receipt key type");
        certificate.verify(certificate.getPublicKey());
        Map<String,String> values=new HashMap<>();
        for(String part:certificate.getSubjectX500Principal().getName(X500Principal.RFC2253).split(",")){
            String[] field=part.split("=",2);
            if(field.length!=2||values.put(field[0],field[1])!=null)throw new GeneralSecurityException("Invalid receipt metadata");
        }
        if(values.size()!=4||!kind.equals(values.get("CN")))throw new GeneralSecurityException("Receipt kind mismatch");
        Receipt record;
        try{
            String number=values.get("O");if(number==null||!number.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();
            record=new Receipt(values.get("OU"),Long.parseLong(number),values.get("L"));
        }catch(IllegalArgumentException invalid){throw new GeneralSecurityException("Invalid receipt metadata",invalid);}
        byte[] challenge=(alias+":"+record.epoch+":"+record.revision+":"+record.digest).getBytes(StandardCharsets.US_ASCII);
        Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign((PrivateKey)keys.getKey(alias,null));signature.update(challenge);
        byte[] proof=signature.sign();signature.initVerify(certificate.getPublicKey());signature.update(challenge);
        if(!signature.verify(proof))throw new GeneralSecurityException("Receipt key/certificate mismatch");
        return record;
    }
    /** Returns only after the provider acknowledged creation and the exact key/metadata was verified. */
    Receipt write(String kind,Receipt requested)throws GeneralSecurityException {
        Receipt previous=read(kind);
        if(previous!=null){
            if(previous.same(requested))return previous;
            if(!CURRENT.equals(kind)||!previous.epoch.equals(requested.epoch)||requested.revision<=previous.revision)
                throw new GeneralSecurityException("Persistence receipt cannot move backward or rotate");
        }
        KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(alias(kind),KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false)
                .setCertificateSubject(new X500Principal("CN="+kind+",OU="+requested.epoch+",O="+requested.revision+",L="+requested.digest))
                .build());
        generator.generateKeyPair();
        Receipt actual=read(kind);if(!requested.same(actual))throw new GeneralSecurityException("Receipt creation was not acknowledged");
        return actual;
    }
    /** Sign only the exact, typed 8/V1 -> 9/V2 reservation with the still-current existing key. */
    byte[] signStructural(LinkingStructuralReservation reservation)throws GeneralSecurityException {
        KeyStore current=structuralKey(reservation);
        java.security.Key key=current.getKey(alias(CURRENT),null);
        if(!(key instanceof PrivateKey))throw new GeneralSecurityException("Current persistence key unavailable");
        Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign((PrivateKey)key);
        signer.update(reservation.signingBytes());byte[] signature=signer.sign();
        verifyStructural(reservation,signature);
        return signature;
    }
    /** Marker certificates are never accepted: trust is the actual database-bound CURRENT alias. */
    void verifyStructural(LinkingStructuralReservation reservation,byte[] signature)throws GeneralSecurityException {
        if(signature==null||signature.length==0||signature.length>128)
            throw new GeneralSecurityException("Invalid structural signature size");
        KeyStore current=structuralKey(reservation);
        java.security.cert.Certificate certificate=current.getCertificate(alias(CURRENT));
        if(!(certificate instanceof X509Certificate))throw new GeneralSecurityException("Current persistence certificate unavailable");
        Signature verifier=Signature.getInstance("SHA256withECDSA");verifier.initVerify(certificate.getPublicKey());
        verifier.update(reservation.signingBytes());
        if(!verifier.verify(signature))throw new GeneralSecurityException("Structural reservation signature differs");
        structuralKey(reservation); // A different revision cannot publish or authenticate this reservation.
    }
    private KeyStore structuralKey(LinkingStructuralReservation reservation)throws GeneralSecurityException {
        if(reservation==null||!reservation.forDatabase(database))
            throw new GeneralSecurityException("Structural database identity differs");
        Receipt actual=read(CURRENT),expected=new Receipt(reservation.epoch,reservation.oldRevision,reservation.oldCommand);
        if(!expected.same(actual))throw new GeneralSecurityException("Structural current receipt differs");
        return keys();
    }
    void deleteOwnedFixtureReceipts()throws GeneralSecurityException {
        KeyStore keys=keys();for(String kind:new String[]{INIT,EVER,CURRENT}){
            String alias=alias(kind);if(keys.containsAlias(alias))keys.deleteEntry(alias);
            if(keys.containsAlias(alias))throw new GeneralSecurityException("Fixture receipt cleanup failed");
        }
    }
}
