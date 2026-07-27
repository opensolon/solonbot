#[cfg(target_os = "windows")]
mod platform {
    use std::ptr;
    use std::slice;
    use windows_sys::Win32::Foundation::ERROR_NOT_FOUND;
    use windows_sys::Win32::Security::Credentials::{
        CredDeleteW, CredFree, CredReadW, CredWriteW, CREDENTIALW, CRED_PERSIST_LOCAL_MACHINE,
        CRED_TYPE_GENERIC,
    };

    fn validate_id(id: &str) -> Result<(), String> {
        if id.is_empty()
            || id.len() > 128
            || !id
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"._:-".contains(&b))
        {
            return Err("凭据标识无效".to_string());
        }
        Ok(())
    }

    fn wide(value: &str) -> Vec<u16> {
        value.encode_utf16().chain(std::iter::once(0)).collect()
    }

    fn target(id: &str) -> Result<Vec<u16>, String> {
        validate_id(id)?;
        Ok(wide(&format!("SolonCode/{}", id)))
    }

    pub fn set(id: &str, value: &str) -> Result<(), String> {
        if value.len() > 8192 {
            return Err("凭据内容过长".to_string());
        }
        let mut target_name = target(id)?;
        let mut username = wide("SolonCode");
        let mut blob = value.as_bytes().to_vec();
        let credential = CREDENTIALW {
            Flags: 0,
            Type: CRED_TYPE_GENERIC,
            TargetName: target_name.as_mut_ptr(),
            Comment: ptr::null_mut(),
            LastWritten: unsafe { std::mem::zeroed() },
            CredentialBlobSize: blob.len() as u32,
            CredentialBlob: blob.as_mut_ptr(),
            Persist: CRED_PERSIST_LOCAL_MACHINE,
            AttributeCount: 0,
            Attributes: ptr::null_mut(),
            TargetAlias: ptr::null_mut(),
            UserName: username.as_mut_ptr(),
        };
        let ok = unsafe { CredWriteW(&credential, 0) };
        blob.fill(0);
        if ok == 0 {
            return Err("无法写入系统凭据库".to_string());
        }
        Ok(())
    }

    pub fn get(id: &str) -> Result<Option<String>, String> {
        let target_name = target(id)?;
        let mut raw: *mut CREDENTIALW = ptr::null_mut();
        let ok = unsafe { CredReadW(target_name.as_ptr(), CRED_TYPE_GENERIC, 0, &mut raw) };
        if ok == 0 {
            let code = std::io::Error::last_os_error()
                .raw_os_error()
                .unwrap_or_default() as u32;
            if code == ERROR_NOT_FOUND {
                return Ok(None);
            }
            return Err("无法读取系统凭据库".to_string());
        }
        if raw.is_null() {
            return Ok(None);
        }
        let bytes = unsafe {
            let credential = &*raw;
            slice::from_raw_parts(
                credential.CredentialBlob,
                credential.CredentialBlobSize as usize,
            )
            .to_vec()
        };
        unsafe { CredFree(raw.cast()) };
        String::from_utf8(bytes)
            .map(Some)
            .map_err(|_| "系统凭据格式无效".to_string())
    }

    pub fn delete(id: &str) -> Result<(), String> {
        let target_name = target(id)?;
        let ok = unsafe { CredDeleteW(target_name.as_ptr(), CRED_TYPE_GENERIC, 0) };
        if ok == 0 {
            let code = std::io::Error::last_os_error()
                .raw_os_error()
                .unwrap_or_default() as u32;
            if code != ERROR_NOT_FOUND {
                return Err("无法删除系统凭据".to_string());
            }
        }
        Ok(())
    }
}

#[tauri::command]
pub fn credential_set(id: &str, value: &str) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    return platform::set(id, value);
    #[cfg(not(target_os = "windows"))]
    {
        let _ = (id, value);
        Err("当前系统暂不支持安全凭据库".to_string())
    }
}

#[tauri::command]
pub fn credential_get(id: &str) -> Result<Option<String>, String> {
    #[cfg(target_os = "windows")]
    return platform::get(id);
    #[cfg(not(target_os = "windows"))]
    {
        let _ = id;
        Err("当前系统暂不支持安全凭据库".to_string())
    }
}

#[tauri::command]
pub fn credential_delete(id: &str) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    return platform::delete(id);
    #[cfg(not(target_os = "windows"))]
    {
        let _ = id;
        Err("当前系统暂不支持安全凭据库".to_string())
    }
}
