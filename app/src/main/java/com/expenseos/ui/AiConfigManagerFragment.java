package com.expenseos.ui;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.util.AiCandidate;
import com.expenseos.util.AiConfigStore;
import com.expenseos.util.AiKeyConfig;
import com.expenseos.util.AiModelConfig;
import com.expenseos.util.AppConfig;

/**
 * Redesigned AI Assistant configuration:
 * Provider spinner (default provider)
 * └ model cards — drag to reorder priority, edit model name, remove
 * └ key rows — masked key + description, edit/remove, add key
 * └ add model
 * Save persists everything (AiConfigStore v2) without losing legacy slots.
 */
public class AiConfigManagerFragment extends Fragment {

    private AiConfigStore store;
    private AiConfigStore.AiConfig config;
    private ModelAdapter adapter;
    private ItemTouchHelper dragHelper;

    @Override
    public View onCreateView(@NonNull LayoutInflater inf, ViewGroup pg, Bundle s) {
        return inf.inflate(R.layout.fragment_ai_config_manager, pg, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        store = new AiConfigStore(requireContext());
        config = store.load().copy();
        // No manual "default provider" picker — drag order sets failover priority,
        // and AiFailoverManager auto-records whichever provider last answered.

        RecyclerView rv = v.findViewById(R.id.rvAiModels);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new ModelAdapter();
        rv.setAdapter(adapter);

        ItemTouchHelper.SimpleCallback drag = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rvx,
                                  @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
                int a = from.getBindingAdapterPosition(), b = to.getBindingAdapterPosition();
                if (a < 0 || b < 0 || a == b) return false;
                config.models.add(b, config.models.remove(a));
                adapter.notifyItemMoved(a, b);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false; // drag handled by the grip handle
            }
        };
        dragHelper = new ItemTouchHelper(drag);
        dragHelper.attachToRecyclerView(rv);

        v.findViewById(R.id.btnAiMgrAddModel).setOnClickListener(x -> showModelDialog(-1));
        v.findViewById(R.id.btnAiMgrSave).setOnClickListener(x -> save());
    }

    private void save() {
        store.save(config);
        Toast.makeText(requireContext(), "✓ AI Config saved!", Toast.LENGTH_SHORT).show();
    }

    private void toast(String m) {
        Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show();
    }

    // ── Model add/edit dialog ────────────────────────────────────────────
    private void showModelDialog(int editPos) {
        AiModelConfig editing = editPos >= 0 ? config.models.get(editPos) : null;
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText etProvider = new EditText(requireContext());
        etProvider.setHint("Provider (gemini, openai, grok, claude, genspark)");
        final EditText etModel = new EditText(requireContext());
        etModel.setHint("Model name (e.g. gemini-2.0-flash)");
        if (editing != null) {
            etProvider.setText(editing.provider);
            etModel.setText(editing.model);
        }
        box.addView(etProvider);
        box.addView(etModel);

        new AlertDialog.Builder(requireContext())
                .setTitle(editing == null ? "Add Model" : "Edit Model")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String prov = etProvider.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                    String model = etModel.getText().toString().trim();
                    if (model.isEmpty()) {
                        toast("Model name is required");
                        return;
                    }
                    if (prov.isEmpty()) prov = AppConfig.PROVIDER_GEMINI;
                    if (editing != null) {
                        editing.provider = prov;
                        editing.model = model;
                        adapter.notifyItemChanged(editPos);
                    } else {
                        config.models.add(new AiModelConfig(prov, model));
                        adapter.notifyItemInserted(config.models.size() - 1);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Key add/edit dialog ─────────────────────────────────────────────
    private void showKeyDialog(AiModelConfig model, int keyPos) {
        AiKeyConfig editing = keyPos >= 0 ? model.keys.get(keyPos) : null;
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText etKey = new EditText(requireContext());
        etKey.setHint("API Key");
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (editing != null) etKey.setText(editing.key);
        final EditText etDesc = new EditText(requireContext());
        etDesc.setHint("Description (e.g. test@gmail.com)");
        etDesc.setInputType(InputType.TYPE_CLASS_TEXT);
        if (editing != null) etDesc.setText(editing.desc);
        box.addView(etKey);
        box.addView(etDesc);

        new AlertDialog.Builder(requireContext())
                .setTitle(editing == null ? "Add API Key" : "Edit API Key")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String key = etKey.getText().toString().trim();
                    if (key.isEmpty()) {
                        toast("API key is required");
                        return;
                    }
                    if (editing != null) {
                        editing.key = key;
                        editing.desc = etDesc.getText().toString().trim();
                        adapter.notifyItemChanged(config.models.indexOf(model));
                    } else {
                        model.keys.add(new AiKeyConfig(key, etDesc.getText().toString().trim()));
                        adapter.notifyItemChanged(config.models.indexOf(model));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Adapter ───────────────────────────────────────────────────────────
    private class ModelAdapter extends RecyclerView.Adapter<ModelAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final TextView tvTitle, tvKeys;
            final RecyclerView rvKeys;
            final ImageButton btnEdit, btnRemove, btnDrag;
            final Button btnAddKey;
            KeyAdapter keyAdapter;

            VH(@NonNull View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvAiModelTitle);
                tvKeys = v.findViewById(R.id.tvAiModelKeys);
                rvKeys = v.findViewById(R.id.rvAiKeys);
                btnEdit = v.findViewById(R.id.btnAiModelEdit);
                btnRemove = v.findViewById(R.id.btnAiModelRemove);
                btnDrag = v.findViewById(R.id.btnAiModelDrag);
                btnAddKey = v.findViewById(R.id.btnAiAddKey);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(requireContext()).inflate(R.layout.item_ai_model, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, @SuppressLint("RecyclerView") int pos) {
            AiModelConfig m = config.models.get(pos);
            h.tvTitle.setText((pos + 1) + ". " + AiCandidate.providerLabel(m.provider) + " — " + m.model);
            h.tvKeys.setText(m.keys.size() + " API key" + (m.keys.size() == 1 ? "" : "s"));

            h.keyAdapter = new KeyAdapter(m);
            h.rvKeys.setLayoutManager(new LinearLayoutManager(requireContext()));
            h.rvKeys.setAdapter(h.keyAdapter);
            h.rvKeys.setNestedScrollingEnabled(false);

            h.btnEdit.setOnClickListener(x -> showModelDialog(pos));
            h.btnRemove.setOnClickListener(x -> new AlertDialog.Builder(requireContext())
                    .setTitle("Remove model?")
                    .setMessage(m.model)
                    .setPositiveButton("Remove", (d, w) -> {
                        int p = config.models.indexOf(m);
                        if (p >= 0) {
                            config.models.remove(p);
                            adapter.notifyItemRemoved(p);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
            h.btnAddKey.setOnClickListener(x -> showKeyDialog(m, -1));
            h.btnDrag.setOnTouchListener((vEv, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && dragHelper != null) {
                    dragHelper.startDrag(h);
                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return config.models.size();
        }
    }

    // ── Key rows ─────────────────────────────────────────────────────────
    private class KeyAdapter extends RecyclerView.Adapter<KeyAdapter.KVH> {

        private final AiModelConfig model;

        KeyAdapter(AiModelConfig model) {
            this.model = model;
        }

        class KVH extends RecyclerView.ViewHolder {
            final EditText etKey, etDesc;
            final ImageButton btnToggle, btnEdit, btnRemove;
            android.text.TextWatcher keyWatcher, descWatcher;

            KVH(@NonNull View v) {
                super(v);
                etKey = v.findViewById(R.id.etAiKeyValue);
                etDesc = v.findViewById(R.id.etAiKeyDesc);
                btnToggle = v.findViewById(R.id.btnAiKeyToggle);
                btnEdit = v.findViewById(R.id.btnAiKeyEdit);
                btnRemove = v.findViewById(R.id.btnAiKeyRemove);
            }
        }

        @NonNull
        @Override
        public KVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(requireContext()).inflate(R.layout.item_ai_key, parent, false);
            return new KVH(v);
        }

        public void onBindViewHolder(@NonNull KVH h, @SuppressLint("RecyclerView") int pos) {
            AiKeyConfig k = model.keys.get(pos);

            // Recycled row — drop the previous key's watchers first, else typing here
            // also silently patches whichever key this view held earlier.
            if (h.keyWatcher != null) h.etKey.removeTextChangedListener(h.keyWatcher);
            if (h.descWatcher != null) h.etDesc.removeTextChangedListener(h.descWatcher);

            h.etKey.setText(k.key);
            h.etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            h.etDesc.setText(k.desc);

            // Write-through on every keystroke, not just focus-loss — tapping Save
            // right after typing a description can never lose it now.
            h.keyWatcher = new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable e) {
                    int p = h.getBindingAdapterPosition();
                    if (p != RecyclerView.NO_POSITION && p < model.keys.size())
                        model.keys.get(p).key = e.toString().trim();
                }
            };
            h.descWatcher = new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable e) {
                    int p = h.getBindingAdapterPosition();
                    if (p != RecyclerView.NO_POSITION && p < model.keys.size())
                        model.keys.get(p).desc = e.toString().trim();
                }
            };
            h.etKey.addTextChangedListener(h.keyWatcher);
            h.etDesc.addTextChangedListener(h.descWatcher);

            h.btnToggle.setOnClickListener(x -> {
                boolean masked = (h.etKey.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
                h.etKey.setInputType(InputType.TYPE_CLASS_TEXT
                        | (masked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                h.etKey.setSelection(h.etKey.getText().length());
            });
            h.btnEdit.setOnClickListener(x -> showKeyDialog(model, pos));
            h.btnRemove.setOnClickListener(x -> {
                int p = model.keys.indexOf(k);
                if (p >= 0) {
                    model.keys.remove(p);
                    adapter.notifyItemChanged(config.models.indexOf(model));
                }
            });
        }

        @Override
        public int getItemCount() {
            return model.keys.size();
        }
    }
}
