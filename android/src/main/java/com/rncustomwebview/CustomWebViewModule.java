package com.rncustomwebview;

import static android.app.Activity.RESULT_OK;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import com.facebook.react.ReactActivity;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.modules.core.PermissionAwareActivity;
import com.facebook.react.modules.core.PermissionListener;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Much of the code here derived from the sample at https://github.com/hushicai/ReactNativeAndroidWebView.
 */
public class CustomWebViewModule extends ReactContextBaseJavaModule implements ActivityEventListener {
    private static final int REQUEST_CAMERA = 1;
    private static final int SELECT_FILE = 2;

    private CustomWebViewPackage aPackage;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri outputFileUri;
    private String intentTypeAfterPermissionGranted;

    private final String[] DEFAULT_MIME_TYPES = {"image/*", "video/*", "audio/*"};

    public CustomWebViewModule(ReactApplicationContext context) {
        super(context);
        context.addActivityEventListener(this);
    }

    @Override
    public String getName() {
        return CustomWebViewModule.class.getSimpleName();
    }

    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {

        if (this.filePathCallback == null) {
            return;
        }

        // based off of which button was pressed, we get an activity result and a file
        // the camera activity doesn't properly return the filename* (I think?) so we use
        // this filename instead
        switch (requestCode) {
            case REQUEST_CAMERA:
                if (resultCode == RESULT_OK) {
                    Log.i("RESULT_OK", outputFileUri.toString());
                    filePathCallback.onReceiveValue(new Uri[]{outputFileUri});
                } else {
                    filePathCallback.onReceiveValue(null);
                }
                break;
            case SELECT_FILE:
                if (resultCode == RESULT_OK && data != null) {
                    Uri result[] = this.getSelectedFiles(data, resultCode);
                    filePathCallback.onReceiveValue(result);
                } else {
                    filePathCallback.onReceiveValue(null);
                }
                break;
            default:
                break;
        }
        this.filePathCallback = null;
    }

    @Override
    public void onNewIntent(Intent intent) {
    }

    private Uri[] getSelectedFiles(Intent data, int resultCode) {
        // we have one files selected
        if (data.getData() != null) {
            if (resultCode == RESULT_OK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                return result;
            } else {
                return null;
            }
        }
        // we have multiple files selected
        if (data.getClipData() != null) {
            final int numSelectedFiles = data.getClipData().getItemCount();
            Uri[] result = new Uri[numSelectedFiles];
            for (int i = 0; i < numSelectedFiles; i++) {
                result[i] = data.getClipData().getItemAt(i).getUri();
            }
            return result;
        }
        return null;
    }

    public boolean startPhotoPickerIntent(
        final ValueCallback<Uri[]> filePathCallback,
        final WebChromeClient.FileChooserParams fileChooserParams) {
        this.filePathCallback = filePathCallback;
        String[] acceptTypes = fileChooserParams.getAcceptTypes();
        if (acceptsImages(acceptTypes)) {
            if (fileChooserParams.isCaptureEnabled()) {
                startCamera(MediaStore.ACTION_IMAGE_CAPTURE);
            } else {
                startFileChooser(fileChooserParams);
            }
        } else if (acceptsVideo(acceptTypes)) {
            if (fileChooserParams.isCaptureEnabled()) {
                startCamera(MediaStore.ACTION_VIDEO_CAPTURE);
            } else {
                startFileChooser(fileChooserParams);
            }
        }
        return true;
    }

    public CustomWebViewPackage getPackage() {
        return this.aPackage;
    }

    public void setPackage(CustomWebViewPackage aPackage) {
        this.aPackage = aPackage;
    }

    private PermissionListener listener = new PermissionListener() {
        public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
            switch (requestCode) {
                case REQUEST_CAMERA: {
                    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        startCamera(intentTypeAfterPermissionGranted);
                        break;
                    } else {
                        filePathCallback.onReceiveValue(null);
                        break;
                    }
                }
                default:
                    break;
            }
            return false;
        }
    };

    @TargetApi(Build.VERSION_CODES.M)
    private void requestPermissions() {
        if (getCurrentActivity() instanceof ReactActivity) {
            ((ReactActivity) getCurrentActivity()).requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA, listener);
        } else {
            ((PermissionAwareActivity) getCurrentActivity()).requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA, listener);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private boolean permissionsGranted() {
        return ActivityCompat.checkSelfPermission(getCurrentActivity(),
            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera(String intentType) {
        if (permissionsGranted()) {
            // bring up a camera picker intent
            // we need to pass a filename for the file to be saved to
            Intent intent = new Intent(intentType);

            // Create the File where the photo should go
            outputFileUri = getOutputFilename(intentType);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, outputFileUri);
            getCurrentActivity().startActivityForResult(intent, REQUEST_CAMERA);
        } else {
            intentTypeAfterPermissionGranted = intentType;
            requestPermissions();
        }
    }

    private void startFileChooser(WebChromeClient.FileChooserParams fileChooserParams) {
        final String[] acceptTypes = fileChooserParams.getAcceptTypes();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final boolean allowMultiple = fileChooserParams.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;

            Intent intent = fileChooserParams.createIntent();
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, getAcceptedMimeType(acceptTypes));
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
            getCurrentActivity().startActivityForResult(intent, SELECT_FILE);
        }
    }

    private File createCapturedFile(String prefix, String suffix) throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = prefix + "_" + timeStamp;
        File storageDir = getReactApplicationContext().getExternalFilesDir(null);
        return File.createTempFile(imageFileName, suffix, storageDir);
    }

    private Boolean acceptsImages(String[] types) {
        return isArrayEmpty(types) || arrayContainsString(types, "image");
    }

    private Boolean acceptsVideo(String[] types) {
        return isArrayEmpty(types) || arrayContainsString(types, "video");
    }

    private Boolean arrayContainsString(String[] array, String pattern) {
        for (String content : array) {
            if (content.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String[] getAcceptedMimeType(String[] types) {
        if (isArrayEmpty(types)) {
            return DEFAULT_MIME_TYPES;
        }
        return types;
    }

    private Uri getOutputFilename(String intentType) {
        String prefix = "";
        String suffix = "";

        if (intentType.equals(MediaStore.ACTION_IMAGE_CAPTURE)) {
            prefix = "image-";
            suffix = ".jpg";
        } else if (intentType.equals(MediaStore.ACTION_VIDEO_CAPTURE)) {
            prefix = "video-";
            suffix = ".mp4";
        }

        String packageName = getReactApplicationContext().getPackageName();
        File capturedFile = null;
        try {
            capturedFile = createCapturedFile(prefix, suffix);
        } catch (IOException e) {
            Log.e("CREATE FILE", "Error occurred while creating the File", e);
            e.printStackTrace();
        }
        return FileProvider.getUriForFile(getReactApplicationContext(), packageName + ".fileprovider", capturedFile);
    }

    private Boolean isArrayEmpty(String[] arr) {
        // when our array returned from getAcceptTypes() has no values set from the webview
        // i.e. <input type="file" />, without any "accept" attr
        // will be an array with one empty string element, afaik
        return arr.length == 0 ||
            (arr.length == 1 && arr[0].length() == 0);
    }
}
