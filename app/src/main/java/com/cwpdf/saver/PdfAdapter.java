package com.cwpdf.saver;

import android.database.Cursor;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cwpdf.saver.db.PdfDatabaseHelper;
import com.cwpdf.saver.util.DownloadEngine;
import com.google.android.material.button.MaterialButton;

public class PdfAdapter extends RecyclerView.Adapter<PdfAdapter.PdfViewHolder> {

    private Cursor cursor;

    public PdfAdapter(Cursor cursor) {
        this.cursor = cursor;
    }

    /**
     * Swaps in the cursor supplied by {@link androidx.loader.content.CursorLoader}.
     *
     * <p>The loader owns this cursor: it re-delivers the <b>same instance</b> on
     * every {@code onLoadFinished} (for example after a stop/start cycle) and
     * closes it itself once it is replaced. Closing it here therefore destroyed
     * the very cursor the adapter was about to read, which surfaced as
     * "attempt to re-open an already-closed object: SQLiteQuery" during layout.
     * Never close it from the adapter.</p>
     */
    public void setCursor(Cursor newCursor) {
        cursor = newCursor;
        notifyDataSetChanged();
    }

    /** Drops the reference on loader reset. The loader closes the cursor itself. */
    public void clearCursor() {
        cursor = null;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PdfViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pdf, parent, false);
        return new PdfViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PdfViewHolder holder, int position) {
        Cursor c = cursor;
        if (c == null || c.isClosed() || !c.moveToPosition(position)) {
            return;
        }

        String title = c.getString(c.getColumnIndexOrThrow(PdfDatabaseHelper.COLUMN_TITLE));
        String url = c.getString(c.getColumnIndexOrThrow(PdfDatabaseHelper.COLUMN_URL));
        String uriString = c.getString(c.getColumnIndexOrThrow(PdfDatabaseHelper.COLUMN_URI));
        String key = c.getString(c.getColumnIndexOrThrow(PdfDatabaseHelper.COLUMN_KEY));
        boolean isEncrypted = c.getInt(c.getColumnIndexOrThrow(PdfDatabaseHelper.COLUMN_IS_ENCRYPTED)) == 1;

        holder.textTitle.setText(title != null && !title.isEmpty() ? title : "Unknown PDF");
        holder.textStatus.setText(isEncrypted ? "🔒 Encrypted PDF" : "📄 Standard PDF");

        holder.btnDownload.setOnClickListener(v -> {
            Toast.makeText(v.getContext(), "Starting download...", Toast.LENGTH_SHORT).show();
            holder.btnDownload.setEnabled(false);
            holder.btnDownload.setText("Downloading...");

            Uri localUri = (uriString != null && !uriString.isEmpty()) ? Uri.parse(uriString) : null;

            DownloadEngine.startDownload(v.getContext(), url, localUri, key, title, isEncrypted, new DownloadEngine.DownloadCallback() {
                @Override
                public void onSuccess(String fileName) {
                    holder.itemView.post(() -> {
                        Toast.makeText(v.getContext(), "Saved to Downloads: " + fileName, Toast.LENGTH_LONG).show();
                        holder.btnDownload.setEnabled(true);
                        holder.btnDownload.setText("Download");
                    });
                }

                @Override
                public void onError(String errorMsg) {
                    holder.itemView.post(() -> {
                        Toast.makeText(v.getContext(), errorMsg, Toast.LENGTH_LONG).show();
                        holder.btnDownload.setEnabled(true);
                        holder.btnDownload.setText("Retry");
                    });
                }
            });
        });
    }

    @Override
    public int getItemCount() {
        Cursor c = cursor;
        return (c == null || c.isClosed()) ? 0 : c.getCount();
    }

    static class PdfViewHolder extends RecyclerView.ViewHolder {
        TextView textTitle;
        TextView textStatus;
        MaterialButton btnDownload;

        PdfViewHolder(@NonNull View itemView) {
            super(itemView);
            textTitle = itemView.findViewById(R.id.text_title);
            textStatus = itemView.findViewById(R.id.text_status);
            btnDownload = itemView.findViewById(R.id.btn_download);
        }
    }
}
