/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.libsignal.ratchet;

import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.kdf.HKDF;
import org.whispersystems.libsignal.kdf.HKDFv3;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.util.ByteUtil;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class RatchetingSession {

  public static void initializeSession(SessionState sessionState, SymmetricSignalProtocolParameters parameters)
      throws InvalidKeyException
  {
    if (isAlice(parameters.getOurBaseKey().getPublicKey(), parameters.getTheirBaseKey())) {
      AliceSignalProtocolParameters.Builder aliceParameters = AliceSignalProtocolParameters.newBuilder();

      aliceParameters.setOurBaseKey(parameters.getOurBaseKey())
                     .setOurIdentityKey(parameters.getOurIdentityKey())
                     .setTheirRatchetKey(parameters.getTheirRatchetKey())
                     .setTheirIdentityKey(parameters.getTheirIdentityKey())
                     .setTheirSignedPreKey(parameters.getTheirBaseKey())
                     .setTheirOneTimePreKey(Optional.<ECPublicKey>absent());

      RatchetingSession.initializeSession(sessionState, aliceParameters.create());
    } else {
      BobSignalProtocolParameters.Builder bobParameters = BobSignalProtocolParameters.newBuilder();

      bobParameters.setOurIdentityKey(parameters.getOurIdentityKey())
                   .setOurRatchetKey(parameters.getOurRatchetKey())
                   .setOurSignedPreKey(parameters.getOurBaseKey())
                   .setOurOneTimePreKey(Optional.<ECKeyPair>absent())
                   .setTheirBaseKey(parameters.getTheirBaseKey())
                   .setTheirIdentityKey(parameters.getTheirIdentityKey());

      RatchetingSession.initializeSession(sessionState, bobParameters.create());
    }
  }

  public static void initializeSession(SessionState sessionState, AliceSignalProtocolParameters parameters)
      throws InvalidKeyException
  {
    try {
      sessionState.setSessionVersion(CiphertextMessage.CURRENT_VERSION);
      sessionState.setRemoteIdentityKey(parameters.getTheirIdentityKey());
      sessionState.setLocalIdentityKey(parameters.getOurIdentityKey().getPublicKey());

      ECKeyPair             sendingRatchetKey = Curve.generateKeyPair();
      ByteArrayOutputStream secrets           = new ByteArrayOutputStream();

      secrets.write(getDiscontinuityBytes());

      secrets.write(Curve.calculateAgreement(parameters.getTheirSignedPreKey(),
                                             parameters.getOurIdentityKey().getPrivateKey()));
      secrets.write(Curve.calculateAgreement(parameters.getTheirIdentityKey().getPublicKey(),
                                             parameters.getOurBaseKey().getPrivateKey()));
      secrets.write(Curve.calculateAgreement(parameters.getTheirSignedPreKey(),
                                             parameters.getOurBaseKey().getPrivateKey()));

      if (parameters.getTheirOneTimePreKey().isPresent()) {
        secrets.write(Curve.calculateAgreement(parameters.getTheirOneTimePreKey().get(),
                                               parameters.getOurBaseKey().getPrivateKey()));
      }

      DerivedKeys             derivedKeys  = calculateDerivedKeys(secrets.toByteArray());
      Pair<RootKey, ChainKey> sendingChain = derivedKeys.getRootKey().createChain(parameters.getTheirRatchetKey(), sendingRatchetKey);

      sessionState.addReceiverChain(parameters.getTheirRatchetKey(), derivedKeys.getChainKey());
      sessionState.setSenderChain(sendingRatchetKey, sendingChain.second());
      sessionState.setRootKey(sendingChain.first());
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  public static void initializeSession(SessionState sessionState, BobSignalProtocolParameters parameters)
      throws InvalidKeyException
  {

    try {
      sessionState.setSessionVersion(CiphertextMessage.CURRENT_VERSION);
      sessionState.setRemoteIdentityKey(parameters.getTheirIdentityKey());
      sessionState.setLocalIdentityKey(parameters.getOurIdentityKey().getPublicKey());

      ByteArrayOutputStream secrets = new ByteArrayOutputStream();

      secrets.write(getDiscontinuityBytes());

      secrets.write(Curve.calculateAgreement(parameters.getTheirIdentityKey().getPublicKey(),
                                             parameters.getOurSignedPreKey().getPrivateKey()));
      secrets.write(Curve.calculateAgreement(parameters.getTheirBaseKey(),
                                             parameters.getOurIdentityKey().getPrivateKey()));
      secrets.write(Curve.calculateAgreement(parameters.getTheirBaseKey(),
                                             parameters.getOurSignedPreKey().getPrivateKey()));

      if (parameters.getOurOneTimePreKey().isPresent()) {
        secrets.write(Curve.calculateAgreement(parameters.getTheirBaseKey(),
                                               parameters.getOurOneTimePreKey().get().getPrivateKey()));
      }

      DerivedKeys derivedKeys = calculateDerivedKeys(secrets.toByteArray());

      sessionState.setSenderChain(parameters.getOurRatchetKey(), derivedKeys.getChainKey());
      sessionState.setRootKey(derivedKeys.getRootKey());
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static byte[] getDiscontinuityBytes() {
    byte[] discontinuity = new byte[32];
    Arrays.fill(discontinuity, (byte) 0xFF);
    return discontinuity;
  }

  private static DerivedKeys calculateDerivedKeys(byte[] masterSecret) {
    HKDF     kdf                = new HKDFv3();
    byte[]   derivedSecretBytes = kdf.deriveSecrets(masterSecret, "WhisperText".getBytes(), 64);
    byte[][] derivedSecrets     = ByteUtil.split(derivedSecretBytes, 32, 32);

    return new DerivedKeys(new RootKey(kdf, derivedSecrets[0]),
                           new ChainKey(kdf, derivedSecrets[1], 0));
  }

  private static boolean isAlice(ECPublicKey ourKey, ECPublicKey theirKey) {
    return ourKey.compareTo(theirKey) < 0;
  }

  private static class DerivedKeys {
    private final RootKey   rootKey;
    private final ChainKey  chainKey;

    private DerivedKeys(RootKey rootKey, ChainKey chainKey) {
      this.rootKey   = rootKey;
      this.chainKey  = chainKey;
    }

    public RootKey getRootKey() {
      return rootKey;
    }

    public ChainKey getChainKey() {
      return chainKey;
    }
  }
}
