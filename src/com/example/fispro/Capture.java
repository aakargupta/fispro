package com.example.fispro;

import android.app.Activity;
import android.app.IntentService;
import android.content.Intent;

public class Capture extends IntentService {

  public Capture(String name) {
		super(name);
		// TODO Auto-generated constructor stub
	}

private int result = Activity.RESULT_CANCELED;

  // Will be called asynchronously be Android
  @Override
  protected void onHandleIntent(Intent intent) {
   
    }

    
} 