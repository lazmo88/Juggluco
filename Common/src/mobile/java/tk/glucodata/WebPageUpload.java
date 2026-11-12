/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */

package tk.glucodata;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static tk.glucodata.MainActivity.poponback;
import static tk.glucodata.MainActivity.setonback;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Small file manager for the files served by the Juggluco web server from
 * getFilesDir()/additions.
 */
public final class WebPageUpload {
    private static final String LOG_ID="WebPageUpload";
    private static final String ADDITIONS="additions";
    private static final String TEMP_PREFIX=".upload-";

    private static View overlay=null;
    private static LinearLayout fileRows=null;
    private static TextView pathView=null;
    private static TextView statusView=null;
    private static com.google.android.material.button.MaterialButton uploadFilesButton=null;
    private static com.google.android.material.button.MaterialButton uploadTreeButton=null;
    private static Button upButton=null;

    private static File rootDirectory=null;
    private static File currentDirectory=null;
    private static File pickerDestination=null;
    private static boolean busy=false;
    private static Runnable backproc=null;

    private WebPageUpload() {}

    private static tk.glucodata.m3.M3Page page=null;

    /** Show the file manager rooted at getFilesDir()/additions (Material 3 page). */
    public static void show(MainActivity context) {
        if(overlay!=null)
            return;
        File root=getRootDirectory(context);
        if(root==null)
            return;
        try {
            rootDirectory=root.getCanonicalFile();
            currentDirectory=rootDirectory;
        }
        catch(IOException e) {
            Log.stack(LOG_ID,"canonical additions",e);
            Applic.argToaster(context,context.getString(R.string.webcantaccessdirectory,ADDITIONS),Toast.LENGTH_LONG);
            return;
        }
        final tk.glucodata.m3.M3Page p=tk.glucodata.m3.M3Page.open(context,R.string.webuploadpages);
        page=p;
        overlay=p.root;
        p.help(R.string.webpageshelp);
        LinearLayout top=p.section((CharSequence)null);
        pathView=p.note(top,displayPath());
        uploadFilesButton=p.button(tk.glucodata.m3.M3Page.Style.TONAL,context.getString(R.string.webuploadfiles),R.drawable.ic_jm_upload_file,()->openFilePicker(context));
        uploadTreeButton=p.button(tk.glucodata.m3.M3Page.Style.TONAL,context.getString(R.string.webuploaddirectory),R.drawable.ic_jm_drive_folder_upload,()->openTreePicker(context));
        p.buttons(top,uploadFilesButton,uploadTreeButton);
        statusView=p.note(top,"");
        statusView.setVisibility(View.GONE);
        fileRows=p.section(R.string.jm_share_files_sec);
        upButton=null;
        p.closeCheck(()-> {
            if(currentDirectory!=null&&rootDirectory!=null&&!sameFile(currentDirectory,rootDirectory)) {
                goUp(context);
                return false;
            }
            return true;
        });
        p.onClose(()-> close(context,p.root));
        refresh(context);
    }

    private static boolean setOverlayMargins(FrameLayout.LayoutParams params,
                                             int left,int top,int right,int bottom) {
        // Preserve the adjustment used by Juggluco for the top system bar.
        int wantedTop=(int)(top*.8f);
        if(params.leftMargin==left&&params.topMargin==wantedTop
                &&params.rightMargin==right&&params.bottomMargin==bottom)
            return false;
        params.leftMargin=left;
        params.topMargin=wantedTop;
        params.rightMargin=right;
        params.bottomMargin=bottom;
        return true;
    }

    private static void configureLayout(LinearLayout panel,LinearLayout browser,
                                        LinearLayout controls,LinearLayout uploadRow,
                                        LinearLayout navigationRow,Button uploadFiles,
                                        Button uploadTree,Button up,Button helpButton,
                                        Button close,int pad,boolean landscape) {
        // Buttons can only have one parent. Detach everything before assembling
        // the orientation-specific hierarchy.
        controls.removeAllViews();
        uploadRow.removeAllViews();
        navigationRow.removeAllViews();
        panel.removeAllViews();

        panel.setOrientation(landscape?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);

        if(landscape) {
            panel.addView(browser,new LinearLayout.LayoutParams(0,MATCH_PARENT,1.0f));
            controls.setPadding(pad,0,0,0);
            addLandscapeButton(controls,uploadFiles);
            addLandscapeButton(controls,uploadTree);
            addLandscapeButton(controls,up);
            addLandscapeButton(controls,helpButton);
            addLandscapeButton(controls,close);
            panel.addView(controls,new LinearLayout.LayoutParams(WRAP_CONTENT,MATCH_PARENT));
        }
        else {
            panel.addView(browser,new LinearLayout.LayoutParams(MATCH_PARENT,0,1.0f));
            controls.setPadding(0,0,0,0);

            uploadRow.addView(uploadFiles,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
            uploadRow.addView(uploadTree,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
            panel.addView(uploadRow,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));

            navigationRow.addView(up,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
            navigationRow.addView(helpButton,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
            navigationRow.addView(close,new LinearLayout.LayoutParams(0,WRAP_CONTENT,1.0f));
            panel.addView(navigationRow,new LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT));
        }
        panel.requestLayout();
    }

    private static void addLandscapeButton(LinearLayout controls,Button button) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(WRAP_CONTENT,0,1.0f);
        params.gravity=Gravity.FILL_HORIZONTAL;
        controls.addView(button,params);
    }

    private static void showHelp(MainActivity context) {
        help.help(R.string.webpageshelp,context);
    }

    private static void close(MainActivity context,View screen) {
        if(overlay!=screen)
            return;
        page=null;
        overlay=null;
        fileRows=null;
        pathView=null;
        statusView=null;
        uploadFilesButton=null;
        uploadTreeButton=null;
        upButton=null;
        // Keep rootDirectory/currentDirectory valid while a background import or
        // recursive delete may still be finishing. show() resets them next time.
        pickerDestination=null;
        backproc=null;
        context.requestRender();
    }

    private static File getRootDirectory(MainActivity context) {
        File directory=new File(context.getFilesDir(),ADDITIONS);
        if(directory.isDirectory()||directory.mkdirs())
            return directory;
        Applic.argToaster(context,context.getString(R.string.webcantcreatedirectory,directory.getAbsolutePath()),Toast.LENGTH_LONG);
        return null;
    }

    private static void openFilePicker(MainActivity context) {
        if(busy||currentDirectory==null)
            return;
        try {
            pickerDestination=currentDirectory.getCanonicalFile();
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivityForResult(intent,MainActivity.REQUEST_WEBPAGES);
        }
        catch(Throwable th) {
            Log.stack(LOG_ID,"openFilePicker",th);
            Applic.argToaster(context,context.getString(R.string.webcantopenfileselector),Toast.LENGTH_LONG);
        }
    }

    private static void openTreePicker(MainActivity context) {
        if(busy||currentDirectory==null)
            return;
        try {
            pickerDestination=currentDirectory.getCanonicalFile();
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivityForResult(intent,MainActivity.REQUEST_WEBTREE);
        }
        catch(Throwable th) {
            Log.stack(LOG_ID,"openTreePicker",th);
            Applic.argToaster(context,context.getString(R.string.webcantopendirectoryselector),Toast.LENGTH_LONG);
        }
    }

    /** Called from MainActivity.onActivityResult for REQUEST_WEBPAGES/REQUEST_WEBTREE. */
    public static void onActivityResult(MainActivity context,int requestCode,int resultCode,Intent data) {
        if(resultCode!=Activity.RESULT_OK||data==null)
            return;

        final File destination=validDirectory(pickerDestination)?pickerDestination:currentDirectory;
        if(!validDirectory(destination)) {
            Applic.argToaster(context,context.getString(R.string.webinvaliddirectory,ADDITIONS),Toast.LENGTH_LONG);
            return;
        }

        if(requestCode==MainActivity.REQUEST_WEBPAGES) {
            final ArrayList<Uri> uris=new ArrayList<>();
            ClipData clip=data.getClipData();
            if(clip!=null) {
                for(int i=0;i<clip.getItemCount();++i)
                    uris.add(clip.getItemAt(i).getUri());
            }
            else {
                Uri uri=data.getData();
                if(uri!=null)
                    uris.add(uri);
            }
            if(!uris.isEmpty())
                importFiles(context,destination,uris);
            return;
        }

        if(requestCode==MainActivity.REQUEST_WEBTREE) {
            Uri tree=data.getData();
            if(tree!=null)
                importTree(context,destination,tree);
        }
    }

    private static void importFiles(MainActivity context,File destination,List<Uri> uris) {
        setBusy(context,true,context.getString(R.string.webuploadingfiles));
        new Thread(()-> {
            ImportResult result=new ImportResult();
            for(Uri uri:uris) {
                String name=getDisplayName(context,uri);
                if(!validName(name)) {
                    ++result.failed;
                    continue;
                }
                if(copyOne(context,uri,new File(destination,name)))
                    ++result.files;
                else
                    ++result.failed;
            }
            finishImport(context,result);
        },"web-file-upload").start();
    }

    private static void importTree(MainActivity context,File destination,Uri treeUri) {
        setBusy(context,true,context.getString(R.string.webuploadingdirectory));
        new Thread(()-> {
            ImportResult result=new ImportResult();
            try {
                String documentId=DocumentsContract.getTreeDocumentId(treeUri);
                Uri documentUri=DocumentsContract.buildDocumentUriUsingTree(treeUri,documentId);
                DocumentInfo rootInfo=getDocumentInfo(context,documentUri);
                if(rootInfo==null||!rootInfo.directory||!validName(rootInfo.name)) {
                    ++result.failed;
                }
                else {
                    File treeDestination=new File(destination,rootInfo.name);
                    if(treeDestination.exists()) {
                        if(!safeExistingDirectory(treeDestination)) {
                            ++result.failed;
                        }
                        else {
                            ++result.directories;
                            copyDocumentDirectory(context,treeUri,documentId,treeDestination,result);
                        }
                    }
                    else if(treeDestination.mkdir()&&validDirectory(treeDestination)) {
                        ++result.directories;
                        copyDocumentDirectory(context,treeUri,documentId,treeDestination,result);
                    }
                    else {
                        ++result.failed;
                    }
                }
            }
            catch(Throwable th) {
                ++result.failed;
                Log.stack(LOG_ID,"importTree",th);
            }
            finishImport(context,result);
        },"web-tree-upload").start();
    }

    private static void copyDocumentDirectory(MainActivity context,Uri treeUri,String parentDocumentId,
                                              File destination,ImportResult result) {
        ContentResolver resolver=context.getContentResolver();
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,parentDocumentId);
        String[] projection={
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try(Cursor cursor=resolver.query(children,projection,null,null,null)) {
            if(cursor==null) {
                ++result.failed;
                return;
            }
            int idColumn=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int typeColumn=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while(cursor.moveToNext()) {
                String id=idColumn>=0?cursor.getString(idColumn):null;
                String name=nameColumn>=0?cursor.getString(nameColumn):null;
                String type=typeColumn>=0?cursor.getString(typeColumn):null;
                if(id==null||!validName(name)) {
                    ++result.failed;
                    continue;
                }
                File target=new File(destination,name);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) {
                    if(target.exists()) {
                        if(!safeExistingDirectory(target)) {
                            ++result.failed;
                            continue;
                        }
                    }
                    else if(!target.mkdir()||!validDirectory(target)) {
                        ++result.failed;
                        continue;
                    }
                    ++result.directories;
                    copyDocumentDirectory(context,treeUri,id,target,result);
                }
                else {
                    if(target.isDirectory()) {
                        ++result.failed;
                        continue;
                    }
                    Uri child=DocumentsContract.buildDocumentUriUsingTree(treeUri,id);
                    if(copyOne(context,child,target))
                        ++result.files;
                    else
                        ++result.failed;
                }
            }
        }
        catch(Throwable th) {
            ++result.failed;
            Log.stack(LOG_ID,"copyDocumentDirectory",th);
        }
    }

    private static DocumentInfo getDocumentInfo(MainActivity context,Uri uri) {
        String[] projection={
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try(Cursor cursor=context.getContentResolver().query(uri,projection,null,null,null)) {
            if(cursor!=null&&cursor.moveToFirst()) {
                int nameColumn=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int typeColumn=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                String name=nameColumn>=0?cursor.getString(nameColumn):null;
                String type=typeColumn>=0?cursor.getString(typeColumn):null;
                return new DocumentInfo(name,DocumentsContract.Document.MIME_TYPE_DIR.equals(type));
            }
        }
        catch(Throwable th) {
            Log.stack(LOG_ID,"getDocumentInfo",th);
        }
        return null;
    }

    private static boolean copyOne(MainActivity context,Uri uri,File destination) {
        File directory=destination.getParentFile();
        if(!validDirectory(directory)||!validName(destination.getName()))
            return false;
        if(destination.isDirectory()) {
            Log.e(LOG_ID,"Destination is a directory: "+destination);
            return false;
        }

        File temporary=null;
        boolean complete=false;
        try {
            temporary=File.createTempFile(TEMP_PREFIX,".tmp",directory);
            try(InputStream input=context.getContentResolver().openInputStream(uri)) {
                if(input==null) {
                    Log.e(LOG_ID,"openInputStream returned null for "+uri);
                    return false;
                }
                try(FileOutputStream output=new FileOutputStream(temporary)) {
                    byte[] buffer=new byte[16*1024];
                    for(int len;(len=input.read(buffer))!=-1;) {
                        if(len!=0)
                            output.write(buffer,0,len);
                    }
                    output.flush();
                    output.getFD().sync();
                    complete=true;
                }
            }
        }
        catch(Throwable th) {
            Log.stack(LOG_ID,"copy to "+destination.getName(),th);
        }

        if(!complete) {
            if(temporary!=null)
                temporary.delete();
            return false;
        }

        if(destination.exists()&&!destination.delete()) {
            Log.e(LOG_ID,"Can't replace "+destination);
            temporary.delete();
            return false;
        }
        if(!temporary.renameTo(destination)) {
            Log.e(LOG_ID,"Can't rename "+temporary+" to "+destination);
            temporary.delete();
            return false;
        }
        return true;
    }

    private static String getDisplayName(MainActivity context,Uri uri) {
        try(Cursor cursor=context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(cursor!=null&&cursor.moveToFirst()) {
                int index=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(index>=0) {
                    String name=cursor.getString(index);
                    if(name!=null&&!name.isEmpty())
                        return name;
                }
            }
        }
        catch(Throwable th) {
            Log.stack(LOG_ID,"getDisplayName",th);
        }
        return uri.getLastPathSegment();
    }

    private static boolean validName(String name) {
        return name!=null&&!name.isEmpty()&&!name.equals(".")&&!name.equals("..")
                &&name.indexOf('/')<0&&name.indexOf('\\')<0&&name.indexOf('\0')<0;
    }

    private static boolean safeExistingDirectory(File directory) {
        if(directory==null||!directory.isDirectory())
            return false;
        try {
            return !isSymbolicLink(directory)&&validDirectory(directory);
        }
        catch(IOException e) {
            Log.stack(LOG_ID,"safeExistingDirectory",e);
            return false;
        }
    }

    private static boolean validDirectory(File directory) {
        if(directory==null||rootDirectory==null||!directory.isDirectory())
            return false;
        try {
            String root=rootDirectory.getCanonicalPath();
            String path=directory.getCanonicalPath();
            return path.equals(root)||path.startsWith(root+File.separator);
        }
        catch(IOException e) {
            Log.stack(LOG_ID,"validDirectory",e);
            return false;
        }
    }

    private static boolean sameFile(File one,File two) {
        if(one==null||two==null)
            return false;
        try {
            return one.getCanonicalFile().equals(two.getCanonicalFile());
        }
        catch(IOException e) {
            return one.equals(two);
        }
    }

    /** Open an uploaded file through Juggluco's local HTTP server. */
    private static void openInBrowser(MainActivity context,File entry) {
        if(entry==null||entry.isDirectory()||rootDirectory==null)
            return;
        try {
            String root=rootDirectory.getCanonicalPath();
            String path=entry.getCanonicalPath();
            String prefix=root+File.separator;
            if(!path.startsWith(prefix))
                return;

            String relative=path.substring(prefix.length()).replace(File.separatorChar,'/');
            String secret=Natives.getApiSecret();
            String secretPath=(secret==null||secret.isEmpty())?"":"/"+Uri.encode(secret);
            String url="http://127.0.0.1:"+Natives.gethttpport()+secretPath+"/"+ADDITIONS+"/"
                    +Uri.encode(relative,"/");
            Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(url));
            context.startActivity(intent);
        }
        catch(Throwable th) {
            Log.stack(LOG_ID,"openInBrowser",th);
        }
    }

    private static void enterDirectory(MainActivity context,File directory) {
        if(busy||!validDirectory(directory))
            return;
        try {
            currentDirectory=directory.getCanonicalFile();
            refresh(context);
        }
        catch(IOException e) {
            Log.stack(LOG_ID,"enterDirectory",e);
        }
    }

    private static void goUp(MainActivity context) {
        if(currentDirectory==null||rootDirectory==null||sameFile(currentDirectory,rootDirectory))
            return;
        File parent=currentDirectory.getParentFile();
        if(validDirectory(parent)) {
            try {
                currentDirectory=parent.getCanonicalFile();
            }
            catch(IOException e) {
                Log.stack(LOG_ID,"goUp",e);
            }
        }
        refresh(context);
    }

    private static void askDelete(MainActivity context,File entry) {
        if(busy||entry==null||!isDirectChild(currentDirectory,entry))
            return;
        int question=entry.isDirectory()?R.string.webdeletedirectoryquestion:R.string.webdeletefilequestion;
        tk.glucodata.m3.M3Page.confirm(context,context.getString(question,entry.getName()),null,context.getString(R.string.delete),()->deleteEntry(context,entry));
    }

    private static boolean isDirectChild(File directory,File entry) {
        if(directory==null||entry==null)
            return false;
        try {
            File parent=entry.getParentFile();
            return parent!=null&&parent.getCanonicalFile().equals(directory.getCanonicalFile())
                    &&validDirectory(directory);
        }
        catch(IOException e) {
            return false;
        }
    }

    private static void deleteEntry(MainActivity context,File entry) {
        setBusy(context,true,context.getString(R.string.webdeleting,entry.getName()));
        new Thread(()-> {
            boolean success=deleteRecursively(entry);
            context.runOnUiThread(()-> {
                String message=context.getString(success?R.string.webdeleted:R.string.webdeletefailed,entry.getName());
                setBusy(context,false,message);
                refresh(context);
                Applic.argToaster(context,message,success?Toast.LENGTH_SHORT:Toast.LENGTH_LONG);
            });
        },"web-file-delete").start();
    }

    private static boolean deleteRecursively(File entry) {
        if(entry==null||rootDirectory==null||sameFile(entry,rootDirectory))
            return false;
        try {
            // Uploaded data never needs symlinks. If one nevertheless exists, delete
            // the link itself rather than following it outside additions or into a cycle.
            if(isSymbolicLink(entry))
                return entry.delete();
            String root=rootDirectory.getCanonicalPath();
            String path=entry.getCanonicalPath();
            if(!path.startsWith(root+File.separator))
                return false;
        }
        catch(IOException e) {
            Log.stack(LOG_ID,"delete canonical",e);
            return false;
        }

        if(entry.isDirectory()) {
            File[] children=entry.listFiles();
            if(children==null)
                return false;
            for(File child:children) {
                if(!deleteRecursively(child))
                    return false;
            }
        }
        return entry.delete();
    }

    private static boolean isSymbolicLink(File file) throws IOException {
        File parent=file.getParentFile();
        File canonicalParent=parent==null?null:parent.getCanonicalFile();
        File normalized=canonicalParent==null?file:new File(canonicalParent,file.getName());
        return !normalized.getCanonicalFile().equals(normalized.getAbsoluteFile());
    }

    private static void finishImport(MainActivity context,ImportResult result) {
        context.runOnUiThread(()-> {
            String text=context.getString(R.string.webuploadresult,result.files,result.directories,result.failed);
            setBusy(context,false,text);
            refresh(context);
            Applic.argToaster(context,text,result.failed==0?Toast.LENGTH_SHORT:Toast.LENGTH_LONG);
        });
    }

    private static void setBusy(MainActivity context,boolean value,String status) {
        busy=value;
        context.runOnUiThread(()-> {
            if(uploadFilesButton!=null)
                uploadFilesButton.setEnabled(!busy);
            if(uploadTreeButton!=null)
                uploadTreeButton.setEnabled(!busy);
            if(statusView!=null)
                statusView.setText(status==null?"":status);
            refresh(context);
        });
    }

    private static void refresh(MainActivity context) {
        final tk.glucodata.m3.M3Page p=page;
        if(p==null||overlay==null||fileRows==null||rootDirectory==null||currentDirectory==null)
            return;
        if(!validDirectory(currentDirectory)) {
            currentDirectory=rootDirectory;
            if(!validDirectory(currentDirectory))
                return;
        }
        boolean atRoot=sameFile(currentDirectory,rootDirectory);
        if(pathView!=null)
            pathView.setText(displayPath());
        if(statusView!=null)
            statusView.setVisibility(statusView.getText().length()>0?View.VISIBLE:View.GONE);
        if(uploadFilesButton!=null)
            uploadFilesButton.setEnabled(!busy);
        if(uploadTreeButton!=null)
            uploadTreeButton.setEnabled(!busy);
        fileRows.removeAllViews();
        if(!atRoot)
            p.link(fileRows,R.drawable.ic_jm_arrow_upward,context.getString(R.string.webup),null,()->goUp(context));
        File[] entries=currentDirectory.listFiles(file->!file.getName().startsWith(TEMP_PREFIX));
        if(entries==null||entries.length==0) {
            p.empty(fileRows,R.drawable.ic_jm_folder_open,context.getString(R.string.webdirectoryempty),context.getString(R.string.jm_share_files_empty_body));
            return;
        }
        Arrays.sort(entries,(a,b)-> {
            if(a.isDirectory()!=b.isDirectory())
                return a.isDirectory()?-1:1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for(File entry:entries) {
            ViewGroup row;
            if(entry.isDirectory())
                row=p.link(fileRows,R.drawable.ic_jm_folder,entry.getName(),null,()->enterDirectory(context,entry));
            else
                row=p.item(fileRows,R.drawable.ic_jm_description,entry.getName(),formatSize(entry.length()),()->openInBrowser(context,entry));
            row.setEnabled(!busy);
            android.widget.ImageButton del=new android.widget.ImageButton(p.ctx);
            del.setImageResource(R.drawable.ic_jm_delete);
            del.setBackgroundResource(android.R.color.transparent);
            del.setColorFilter(p.color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            del.setContentDescription(context.getString(R.string.delete)+" "+entry.getName());
            del.setPadding(p.dp(12),p.dp(12),p.dp(12),p.dp(12));
            del.setEnabled(!busy);
            del.setOnClickListener(v->askDelete(context,entry));
            android.widget.FrameLayout end=tk.glucodata.m3.M3Page.end(row);
            end.removeAllViews();
            end.addView(del);
            end.setVisibility(View.VISIBLE);
        }
    }
    private static String displayPath() {
        try {
            String root=rootDirectory.getCanonicalPath();
            String current=currentDirectory.getCanonicalPath();
            String relative=current.substring(root.length()).replace(File.separatorChar,'/');
            return "additions"+(relative.isEmpty()?"/":relative+"/");
        }
        catch(IOException e) {
            return "additions/";
        }
    }

    private static String formatSize(long bytes) {
        if(bytes<1024)
            return bytes+" B";
        if(bytes<1024L*1024L)
            return String.format(java.util.Locale.US,"%.1f KiB",bytes/1024.0);
        return String.format(java.util.Locale.US,"%.1f MiB",bytes/(1024.0*1024.0));
    }

    private static final class ImportResult {
        int files=0;
        int directories=0;
        int failed=0;
    }

    private static final class DocumentInfo {
        final String name;
        final boolean directory;
        DocumentInfo(String name,boolean directory) {
            this.name=name;
            this.directory=directory;
        }
    }
}
