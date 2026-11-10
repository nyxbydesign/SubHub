package com.subhub.app.remote;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Fresh relationship-outcome proof. It never provisions credentials, consumes a TOTP or permits ordinary sync. */
final class PairingCompletionProof {
    private enum Phase { DOM_ACTIVE, SUB_PENDING, DOM_READY }
    private static final String DOMAIN="subhub.pairing.committed-outcome.v1";

    static final class Offer {
        final String dom,sub,generation,clientNonce;
        final FormalRelationshipClock.Sample began;
        Offer(String dom,String sub,String generation,String clientNonce,FormalRelationshipClock.Sample began){
            this.dom=RemoteProtocol.peerId(dom);this.sub=RemoteProtocol.peerId(sub);
            if(dom.equals(sub))throw new IllegalArgumentException("Distinct pairing devices required");
            this.generation=RemoteProtocol.identifier(generation);this.clientNonce=nonce(clientNonce);
            began.requireValid();this.began=began;
        }
        Exchange challenge(String serverNonce){return new Exchange(this,serverNonce);}
    }
    static final class Exchange {
        final Offer offer;final String serverNonce;
        private Exchange(Offer offer,String serverNonce){this.offer=offer;this.serverNonce=nonce(serverNonce);}
        String active(String existingSecret)throws GeneralSecurityException{return mac(existingSecret,Phase.DOM_ACTIVE);}
        String pending(String existingSecret)throws GeneralSecurityException{return mac(existingSecret,Phase.SUB_PENDING);}
        String ready(String existingSecret)throws GeneralSecurityException{return mac(existingSecret,Phase.DOM_READY);}
        void verifyActive(String existingSecret,String proof,FormalRelationshipClock.Sample now)throws GeneralSecurityException{
            verify(existingSecret,proof,Phase.DOM_ACTIVE,now);
        }
        void verifyPending(String existingSecret,String proof,FormalRelationshipClock.Sample now)throws GeneralSecurityException{
            verify(existingSecret,proof,Phase.SUB_PENDING,now);
        }
        Verified verifyReady(String existingSecret,String proof,FormalRelationshipClock.Sample now)throws GeneralSecurityException{
            verify(existingSecret,proof,Phase.DOM_READY,now);
            return new Verified(this,credentialDigest(existingSecret));
        }
        private void verify(String existingSecret,String proof,Phase phase,FormalRelationshipClock.Sample now)throws GeneralSecurityException{
            if(!PairingLifecycleClock.freshProof(offer.began,now))throw new GeneralSecurityException("Pairing outcome proof expired");
            if(proof==null||!proof.matches("[0-9a-f]{64}")||!MessageDigest.isEqual(mac(existingSecret,phase).getBytes(StandardCharsets.US_ASCII),proof.getBytes(StandardCharsets.US_ASCII)))
                throw new GeneralSecurityException("Pairing outcome proof differs");
        }
        private String mac(String existingSecret,Phase phase)throws GeneralSecurityException{
            requireSecret(existingSecret);byte[] key=existingSecret.getBytes(StandardCharsets.US_ASCII);
            try{
                Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
                return hex(mac.doFinal(transcript(phase)));
            }finally{Arrays.fill(key,(byte)0);}
        }
        private byte[] transcript(Phase phase){
            try{
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(DataOutputStream out=new DataOutputStream(bytes)){
                    field(out,DOMAIN);out.writeInt(1);field(out,phase.name());field(out,offer.dom);field(out,offer.sub);
                    field(out,offer.generation);field(out,offer.clientNonce);field(out,serverNonce);
                }
                return bytes.toByteArray();
            }catch(IOException impossible){throw new AssertionError(impossible);}
        }
    }
    /** Constructible only after a fresh DOM_READY proof was verified with an existing credential. */
    static final class Verified {
        final String dom,sub,generation,credentialDigest;
        private final FormalRelationshipClock.Sample began;
        private Verified(Exchange exchange,String credentialDigest){
            dom=exchange.offer.dom;sub=exchange.offer.sub;generation=exchange.offer.generation;
            this.credentialDigest=credentialDigest;began=exchange.offer.began;
        }
        boolean validFor(String localSub,String remoteDom,String generation,String existingCredentialDigest,FormalRelationshipClock.Sample now){
            return sub.equals(localSub)&&dom.equals(remoteDom)&&this.generation.equals(generation)
                    &&credentialDigest.equals(existingCredentialDigest)&&PairingLifecycleClock.freshProof(began,now);
        }
    }

    private PairingCompletionProof(){ }
    static Offer offer(String dom,String sub,String generation,FormalRelationshipClock.Sample began){
        return new Offer(dom,sub,generation,freshNonce(),began);
    }
    static String freshNonce(){byte[] nonce=new byte[32];new SecureRandom().nextBytes(nonce);return hex(nonce);}
    static String nonce(String value){
        if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid pairing nonce");return value;
    }
    static String credentialDigest(String existingSecret)throws GeneralSecurityException{
        requireSecret(existingSecret);byte[] bytes=existingSecret.getBytes(StandardCharsets.US_ASCII);
        try{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}finally{Arrays.fill(bytes,(byte)0);}
    }
    private static void requireSecret(String existingSecret)throws GeneralSecurityException{
        if(existingSecret==null||!existingSecret.matches("[A-Z2-7]{32}"))throw new GeneralSecurityException("Existing pairing credential required");
    }
    private static void field(DataOutputStream out,String value)throws IOException{
        byte[] bytes=value.getBytes(StandardCharsets.US_ASCII);out.writeInt(bytes.length);out.write(bytes);
    }
    private static String hex(byte[] bytes){
        StringBuilder result=new StringBuilder(bytes.length*2);
        for(byte value:bytes)result.append(Character.forDigit((value>>>4)&15,16)).append(Character.forDigit(value&15,16));return result.toString();
    }
}
