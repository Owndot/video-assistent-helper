//! Bounded application records carried inside authenticated channel records.
//! Parsing checks exact lengths before any allocation proportional to input.

use crate::Error;

pub const MAX_CONTROL: usize = 16 * 1024;
pub const MAX_MEDIA: usize = 60 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AppRecord {
    Control(Vec<u8>),
    Media { transfer: u32, offset: u64, data: Vec<u8> },
    End { transfer: u32, total: u64 },
}

impl AppRecord {
    pub fn encode(&self) -> Result<Vec<u8>, Error> {
        let mut out = Vec::new();
        match self {
            AppRecord::Control(b) => {
                if b.len() > MAX_CONTROL {
                    return Err(Error::TooLarge);
                }
                out.push(1);
                out.extend_from_slice(&(b.len() as u16).to_be_bytes());
                out.extend_from_slice(b);
            }
            AppRecord::Media { transfer, offset, data } => {
                if data.len() > MAX_MEDIA || data.is_empty() {
                    return Err(Error::TooLarge);
                }
                out.push(2);
                out.extend_from_slice(&transfer.to_be_bytes());
                out.extend_from_slice(&offset.to_be_bytes());
                out.extend_from_slice(&(data.len() as u16).to_be_bytes());
                out.extend_from_slice(data);
            }
            AppRecord::End { transfer, total } => {
                out.push(3);
                out.extend_from_slice(&transfer.to_be_bytes());
                out.extend_from_slice(&total.to_be_bytes());
            }
        }
        Ok(out)
    }

    pub fn decode(b: &[u8]) -> Result<Self, Error> {
        let be16 = |at: usize| u16::from_be_bytes([b[at], b[at + 1]]) as usize;
        let be32 = |at: usize| u32::from_be_bytes(b[at..at + 4].try_into().unwrap());
        let be64 = |at: usize| u64::from_be_bytes(b[at..at + 8].try_into().unwrap());
        match b.first() {
            Some(1) if b.len() >= 3 => {
                let n = be16(1);
                if n > MAX_CONTROL || b.len() != 3 + n {
                    return Err(Error::Malformed);
                }
                Ok(AppRecord::Control(b[3..].to_vec()))
            }
            Some(2) if b.len() >= 15 => {
                let n = be16(13);
                if n == 0 || n > MAX_MEDIA || b.len() != 15 + n {
                    return Err(Error::Malformed);
                }
                Ok(AppRecord::Media { transfer: be32(1), offset: be64(5), data: b[15..].to_vec() })
            }
            Some(3) if b.len() == 13 => Ok(AppRecord::End { transfer: be32(1), total: be64(5) }),
            _ => Err(Error::Malformed),
        }
    }
}
