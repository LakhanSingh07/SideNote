use std::path::PathBuf;
pub fn choose(name:&str,save:bool)->Result<Option<PathBuf>,String>{
    #[cfg(windows)]{
        use windows_sys::Win32::UI::Controls::Dialogs::*;
        let mut buffer=vec![0u16;32768];let value:Vec<u16>=name.encode_utf16().take(250).collect();buffer[..value.len()].copy_from_slice(&value);
        let filter:Vec<u16>="SideNote backups\0*.zip\0All files\0*.*\0\0".encode_utf16().collect();
        let mut dialog:OPENFILENAMEW=unsafe{std::mem::zeroed()};dialog.lStructSize=std::mem::size_of::<OPENFILENAMEW>() as u32;dialog.lpstrFile=buffer.as_mut_ptr();dialog.nMaxFile=buffer.len() as u32;dialog.lpstrFilter=filter.as_ptr();
        dialog.Flags=OFN_EXPLORER|OFN_NOCHANGEDIR|if save{OFN_OVERWRITEPROMPT}else{OFN_FILEMUSTEXIST};
        let selected=unsafe{if save{GetSaveFileNameW(&mut dialog)}else{GetOpenFileNameW(&mut dialog)}};
        if selected==0 {let code=unsafe{CommDlgExtendedError()};return if code==0{Ok(None)}else{Err(format!("File picker failed ({code})"))}}
        let end=buffer.iter().position(|c|*c==0).ok_or("Invalid file path")?;Ok(Some(PathBuf::from(String::from_utf16_lossy(&buffer[..end]))))
    }
    #[cfg(not(windows))]{let _=(name,save);Err("Native picker is available on Windows".into())}
}
