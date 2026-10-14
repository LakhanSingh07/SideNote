//! Device identity primitives for a future mutually-authenticated pairing protocol.
//! NOT wired into LAN transport yet. Do not present legacy shared-key LAN as secure pairing.
use chacha20poly1305::aead::{OsRng,rand_core::RngCore};
use ed25519_dalek::{Signature,Signer,SigningKey,Verifier,VerifyingKey};

const DOMAIN:&[u8]=b"SideNote/device-auth/v1\0";

pub struct DeviceIdentity { key: SigningKey }
impl DeviceIdentity {
    #[allow(dead_code)]
    pub fn generate()->Self {
        let mut seed=[0u8;32]; OsRng.fill_bytes(&mut seed);
        Self { key: SigningKey::from_bytes(&seed) }
    }
    pub fn from_seed_hex(seed_hex:&str)->Result<Self,String>{
        let bytes=hex::decode(seed_hex).map_err(|_|"Invalid device seed".to_string())?;
        let seed:[u8;32]=bytes.try_into().map_err(|_|"Invalid device seed length".to_string())?;
        Ok(Self{key:SigningKey::from_bytes(&seed)})
    }
    pub fn generate_seed_hex()->String{
        let mut seed=[0u8;32]; OsRng.fill_bytes(&mut seed);hex::encode(seed)
    }
    pub fn public_key_hex(&self)->String { hex::encode(self.key.verifying_key().to_bytes()) }
    pub fn sign_challenge(&self, peer_public_hex:&str, challenge:&[u8;32])->Result<String,String>{
        let peer=parse_public(peer_public_hex)?;
        let bytes=challenge_message(&self.key.verifying_key().to_bytes(),&peer.to_bytes(),challenge);
        Ok(hex::encode(self.key.sign(&bytes).to_bytes()))
    }
}
fn parse_public(value:&str)->Result<VerifyingKey,String>{
    let raw=hex::decode(value).map_err(|_|"Invalid public key encoding".to_string())?;
    let raw:[u8;32]=raw.try_into().map_err(|_|"Invalid public key length".to_string())?;
    VerifyingKey::from_bytes(&raw).map_err(|_|"Invalid public key".to_string())
}
fn challenge_message(signer:&[u8;32],peer:&[u8;32],challenge:&[u8;32])->Vec<u8>{
    let mut data=Vec::with_capacity(DOMAIN.len()+96);
    data.extend_from_slice(DOMAIN);data.extend_from_slice(signer);data.extend_from_slice(peer);data.extend_from_slice(challenge);data
}
pub fn verify_challenge(signer_public_hex:&str,our_public_hex:&str,challenge:&[u8;32],signature_hex:&str)->Result<(),String>{
    let signer=parse_public(signer_public_hex)?;
    let our=parse_public(our_public_hex)?;
    let bytes=hex::decode(signature_hex).map_err(|_|"Invalid signature encoding".to_string())?;
    let sig_bytes:[u8;64]=bytes.try_into().map_err(|_|"Invalid signature length".to_string())?;
    let signature=Signature::from_bytes(&sig_bytes);
    signer.verify(&challenge_message(&signer.to_bytes(),&our.to_bytes(),challenge),&signature)
        .map_err(|_|"Device signature verification failed".to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn valid_proof(){let a=DeviceIdentity::generate();let b=DeviceIdentity::generate();let challenge=[7u8;32];let signed=a.sign_challenge(&b.public_key_hex(),&challenge).unwrap();assert!(verify_challenge(&a.public_key_hex(),&b.public_key_hex(),&challenge,&signed).is_ok());}
    #[test] fn reject_wrong_peer(){let a=DeviceIdentity::generate();let b=DeviceIdentity::generate();let c=DeviceIdentity::generate();let challenge=[8u8;32];let signed=a.sign_challenge(&b.public_key_hex(),&challenge).unwrap();assert!(verify_challenge(&a.public_key_hex(),&c.public_key_hex(),&challenge,&signed).is_err());}
    #[test] fn reject_replay_with_new_challenge(){let a=DeviceIdentity::generate();let b=DeviceIdentity::generate();let signed=a.sign_challenge(&b.public_key_hex(),&[1u8;32]).unwrap();assert!(verify_challenge(&a.public_key_hex(),&b.public_key_hex(),&[2u8;32],&signed).is_err());}
    #[test] fn reject_corrupt_signature(){let a=DeviceIdentity::generate();let b=DeviceIdentity::generate();assert!(verify_challenge(&a.public_key_hex(),&b.public_key_hex(),&[3u8;32],"0000").is_err());}
}
