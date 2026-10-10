package com.oemtools.poldock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 런처 진입점. 앱을 실행하면 설정 화면 대신 독을 띄운다.
 * 설정 화면은 독의 손잡이(≡)를 더블탭하면 열린다.
 */
public class LaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (OverlayService.canOverlay(this)) {
            startService(new Intent(this, OverlayService.class));
            Toast.makeText(this, "독을 띄웠습니다 · 손잡이(≡) 더블탭으로 설정",
                    Toast.LENGTH_SHORT).show();
        } else {
            // 오버레이 권한이 없으면 설정 화면으로
            startActivity(new Intent(this, MainActivity.class));
        }
        finish();
    }
}
