package com.cwpdf.saver;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cwpdf.saver.provider.PdfContentProvider;
import com.cwpdf.saver.db.PdfDatabaseHelper;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

public class MainActivity extends AppCompatActivity implements LoaderManager.LoaderCallbacks<Cursor> {

    private static final int PDF_LOADER_ID = 1;
    
    private RecyclerView recyclerPdfs;
    private LinearLayout emptyState;
    private PdfAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerPdfs = findViewById(R.id.recycler_pdfs);
        emptyState = findViewById(R.id.empty_state);
        ExtendedFloatingActionButton fabCommunity = findViewById(R.id.fab_community);

        recyclerPdfs.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PdfAdapter(null);
        recyclerPdfs.setAdapter(adapter);

        fabCommunity.setOnClickListener(v -> openUrl("https://t.me/+OQA0X-ECCHI4ZmU1"));

        LoaderManager.getInstance(this).initLoader(PDF_LOADER_ID, null, this);
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    @NonNull
    @Override
    public Loader<Cursor> onCreateLoader(int id, @Nullable Bundle args) {
        String[] projection = {
                PdfDatabaseHelper.COLUMN_ID,
                PdfDatabaseHelper.COLUMN_TITLE,
                PdfDatabaseHelper.COLUMN_URL,
                PdfDatabaseHelper.COLUMN_URI,
                PdfDatabaseHelper.COLUMN_KEY,
                PdfDatabaseHelper.COLUMN_IS_ENCRYPTED,
                PdfDatabaseHelper.COLUMN_TIMESTAMP
        };
        
        return new CursorLoader(
                this,
                PdfContentProvider.CONTENT_URI,
                projection,
                null,
                null,
                PdfDatabaseHelper.COLUMN_TIMESTAMP + " DESC" // Newest first
        );
    }

    @Override
    public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor data) {
        adapter.setCursor(data);
        if (data != null && data.getCount() > 0) {
            recyclerPdfs.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
        } else {
            recyclerPdfs.setVisibility(View.GONE);
            emptyState.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onLoaderReset(@NonNull Loader<Cursor> loader) {
        // Just drop the reference - the loader closes the cursor itself.
        adapter.clearCursor();
    }
}
