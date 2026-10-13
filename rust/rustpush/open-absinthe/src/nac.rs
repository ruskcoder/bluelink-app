use serde::{Deserialize, Serialize};


pub fn bin_serialize<S>(x: &[u8], s: S) -> Result<S::Ok, S::Error>
where
    S: Serializer,
{
    s.serialize_bytes(x)
}

use serde::{Deserializer, Serializer, de};

use crate::AbsintheError;

pub fn bin_deserialize_mac<'de, D>(d: D) -> Result<[u8; 6], D::Error>
where
    D: Deserializer<'de>,
{
    bin_deserialize(d).map(|i| i.try_into().unwrap())
}

pub fn bin_deserialize<'de, D>(d: D) -> Result<Vec<u8>, D::Error>
where
    D: Deserializer<'de>,
{
    use core::fmt;

    struct DataVisitor;

    impl<'de> de::Visitor<'de> for DataVisitor {
        type Value = Vec<u8>;

        fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
            formatter.write_str("a byte array")
        }

        fn visit_bytes<E>(self, v: &[u8]) -> Result<Self::Value, E>
        where
            E: de::Error,
        {
            self.visit_byte_buf(v.to_owned())
        }

        fn visit_byte_buf<E>(self, v: Vec<u8>) -> Result<Self::Value, E>
        where
            E: de::Error,
        {
            Ok(v.into())
        }
    }

    d.deserialize_byte_buf(DataVisitor)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HardwareConfig {
    pub product_name: String,
    #[serde(serialize_with = "bin_serialize", deserialize_with = "bin_deserialize_mac")]
    pub io_mac_address: [u8; 6],
    pub platform_serial_number: String,
    pub platform_uuid: String,
    pub root_disk_uuid: String,
    pub board_id: String,
    pub os_build_num: String,
    #[serde(serialize_with = "bin_serialize", deserialize_with = "bin_deserialize")]
    pub platform_serial_number_enc: Vec<u8>, // Gq3489ugfi
    #[serde(serialize_with = "bin_serialize", deserialize_with = "bin_deserialize")]
    pub platform_uuid_enc: Vec<u8>, // Fyp98tpgj
    #[serde(serialize_with = "bin_serialize", deserialize_with = "bin_deserialize")]
    pub root_disk_uuid_enc: Vec<u8>, // kbjfrfpoJU
    #[serde(serialize_with = "bin_serialize", deserialize_with = "bin_deserialize")]
    pub rom: Vec<u8>,
    #[serde(serialize_with = "bin_serialize", deserialize_with = "bin_deserialize")]
    pub rom_enc: Vec<u8>, // oycqAZloTNDm
    pub mlb: String,
    #[serde(serialize_with = "bin_serialize", deserialize_with = "bin_deserialize")]
    pub mlb_enc: Vec<u8>, // abKPld1EcMni
}

impl HardwareConfig {
    pub fn from_validation_data(_data: &[u8]) -> Result<HardwareConfig, AbsintheError> {
        Err(AbsintheError::unavailable())
    }
}

/// The three-phase validation: `new` produces the session-info request, `key_establishment` takes
/// Apple's reply, and `sign` produces the validation data.
///
/// Holds the backend's session across all three calls, because the native validation context is
/// produced by the first and consumed by the last.
#[cfg(feature = "emulator")]
pub struct ValidationCtx {
    session: crate::emulator::Session,
}

// The backend is single-threaded and self-contained; it is only ever used from one place at a
// time behind rustpush's own locking.
#[cfg(feature = "emulator")]
unsafe impl Send for ValidationCtx { }

#[cfg(feature = "emulator")]
impl ValidationCtx {
    pub fn new(cert_chain: &[u8], out_request_bytes: &mut Vec<u8>, hw_config: &HardwareConfig) -> Result<ValidationCtx, AbsintheError> {
        let mut session = crate::emulator::Session::new(hw_config)?;
        session.init(cert_chain, out_request_bytes)?;
        Ok(ValidationCtx { session })
    }

    pub fn key_establishment(&mut self, response: &[u8]) -> Result<(), AbsintheError> {
        self.session.key_establishment(response)
    }

    pub fn sign(&mut self) -> Result<Vec<u8>, AbsintheError> {
        self.session.sign()
    }
}

/// The stub that stands in when the backend is not compiled in. Same interface, so everything
/// above it builds; nothing here can produce validation data.
#[cfg(not(feature = "emulator"))]
pub struct ValidationCtx;

#[cfg(not(feature = "emulator"))]
impl ValidationCtx {
    pub fn new(_cert_chain: &[u8], _out_request_bytes: &mut Vec<u8>, _hw_config: &HardwareConfig) -> Result<ValidationCtx, AbsintheError> {
        Err(AbsintheError::no_backend())
    }

    pub fn key_establishment(&mut self, _response: &[u8]) -> Result<(), AbsintheError> {
        Err(AbsintheError::no_backend())
    }

    pub fn sign(&mut self) -> Result<Vec<u8>, AbsintheError> {
        Err(AbsintheError::no_backend())
    }
}
