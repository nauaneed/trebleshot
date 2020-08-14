/*
 * Copyright (C) 2019 Veli Tasalı
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.genonbeta.TrebleShot.activity;

import android.content.*;
import android.content.res.ColorStateList;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import com.genonbeta.TrebleShot.R;
import com.genonbeta.TrebleShot.app.Activity;
import com.genonbeta.TrebleShot.database.Kuick;
import com.genonbeta.TrebleShot.dialog.DialogUtils;
import com.genonbeta.TrebleShot.dialog.ToggleMultipleTransferDialog;
import com.genonbeta.TrebleShot.dialog.TransferInfoDialog;
import com.genonbeta.TrebleShot.exception.ConnectionNotFoundException;
import com.genonbeta.TrebleShot.fragment.TransferItemDetailExplorerFragment;
import com.genonbeta.TrebleShot.object.*;
import com.genonbeta.TrebleShot.service.backgroundservice.AttachedTaskListener;
import com.genonbeta.TrebleShot.service.backgroundservice.BaseAttachableAsyncTask;
import com.genonbeta.TrebleShot.service.backgroundservice.TaskMessage;
import com.genonbeta.TrebleShot.task.FileTransferTask;
import com.genonbeta.TrebleShot.util.AppUtils;
import com.genonbeta.TrebleShot.util.FileUtils;
import com.genonbeta.TrebleShot.util.Transfers;
import com.genonbeta.android.database.SQLQuery;
import com.genonbeta.android.framework.io.StreamInfo;
import com.genonbeta.android.framework.ui.callback.SnackbarPlacementProvider;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

/**
 * Created by: veli
 * Date: 5/23/17 1:43 PM
 */

public class TransferDetailActivity extends Activity implements SnackbarPlacementProvider, AttachedTaskListener
{
    public static final String
            TAG = TransferDetailActivity.class.getSimpleName(),
            ACTION_LIST_TRANSFERS = "com.genonbeta.TrebleShot.action.LIST_TRANSFERS",
            EXTRA_TRANSFER = "extraTransfer",
            EXTRA_TRANSFER_ITEM_ID = "extraTransferItemId",
            EXTRA_DEVICE = "extraDevice",
            EXTRA_TRANSFER_TYPE = "extraTransferType";

    public static final int REQUEST_ADD_DEVICES = 5045;

    private OnBackPressedListener mBackPressedListener;
    private Transfer mTransfer;
    private IndexOfTransferGroup mIndex;
    private TransferItem mTransferItem;
    private LoadedMember mMember;
    private MenuItem mRetryMenu;
    private MenuItem mShowFilesMenu;
    private MenuItem mAddDeviceMenu;
    private MenuItem mLimitMenu;
    private MenuItem mToggleBrowserShare;
    private int mColorActive;
    private int mColorNormal;
    private CrunchLatestDataTask mDataCruncher;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            if (Kuick.ACTION_DATABASE_CHANGE.equals(intent.getAction())) {
                Kuick.BroadcastData data = Kuick.toData(intent);

                if (Kuick.TABLE_TRANSFER.equals(data.tableName))
                    reconstructGroup();
                else if (Kuick.TABLE_TRANSFERITEM.equals(data.tableName) && (data.inserted || data.removed))
                    updateCalculations();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_view_transfer);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_close_white_24dp);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        mColorActive = ContextCompat.getColor(this, AppUtils.getReference(this, R.attr.colorError));
        mColorNormal = ContextCompat.getColor(this, AppUtils.getReference(this, R.attr.colorAccent));

        if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData() != null) {
            try {
                StreamInfo streamInfo = StreamInfo.getStreamInfo(this, getIntent().getData());

                Log.d(TAG, "Requested file is: " + streamInfo.friendlyName);

                ContentValues fileData = getDatabase().getFirstFromTable(new SQLQuery.Select(Kuick.TABLE_TRANSFERITEM)
                        .setWhere(Kuick.FIELD_TRANSFER_FILE + "=? AND " + Kuick.FIELD_TRANSFER_TYPE + "=?",
                                streamInfo.friendlyName, TransferItem.Type.INCOMING.toString()));

                if (fileData == null)
                    throw new Exception("File is not found in the database");

                mTransferItem = new TransferItem();
                mTransferItem.reconstruct(getDatabase().getWritableDatabase(), getDatabase(), fileData);

                getDatabase().reconstruct(mTransfer);

                getIntent().setAction(ACTION_LIST_TRANSFERS)
                        .putExtra(EXTRA_TRANSFER, mTransfer);

                new TransferInfoDialog(TransferDetailActivity.this, mIndex, mTransferItem,
                        mMember == null ? null : mMember.deviceId).show();

                Log.d(TAG, "Created instance from an file intent. Original has been cleaned " +
                        "and changed to open intent");
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, R.string.mesg_notValidTransfer, Toast.LENGTH_SHORT).show();
            }
        } else if (ACTION_LIST_TRANSFERS.equals(getIntent().getAction()) && getIntent().hasExtra(EXTRA_TRANSFER)) {
            try {
                setTransfer(getIntent().getParcelableExtra(EXTRA_TRANSFER));

                if (getIntent().hasExtra(EXTRA_TRANSFER_ITEM_ID) && getIntent().hasExtra(EXTRA_DEVICE)
                        && getIntent().hasExtra(EXTRA_TRANSFER_TYPE)) {
                    long requestId = getIntent().getLongExtra(EXTRA_TRANSFER_ITEM_ID, -1);
                    Device device = getIntent().getParcelableExtra(EXTRA_DEVICE);

                    try {
                        TransferItem.Type type = TransferItem.Type.valueOf(getIntent().getStringExtra(
                                EXTRA_TRANSFER_TYPE));

                        TransferItem object = new TransferItem(mTransfer.id, requestId, type);
                        getDatabase().reconstruct(object);

                        new TransferInfoDialog(TransferDetailActivity.this, mIndex, object, device.uid).show();
                    } catch (Exception e) {
                        // do nothing
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (mTransfer == null)
            finish();
        else {
            Bundle bundle = new Bundle();
            bundle.putLong(TransferItemDetailExplorerFragment.ARG_GROUP_ID, mTransfer.id);
            bundle.putString(TransferItemDetailExplorerFragment.ARG_PATH, mTransferItem == null
                    || mTransferItem.directory == null ? null : mTransferItem.directory);

            TransferItemDetailExplorerFragment fragment = getExplorerFragment();

            if (fragment == null) {
                fragment = (TransferItemDetailExplorerFragment) getSupportFragmentManager().getFragmentFactory().instantiate(
                        getClassLoader(), TransferItemDetailExplorerFragment.class.getName());
                fragment.setArguments(bundle);

                FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

                transaction.add(R.id.activity_transaction_content_frame, fragment);
                transaction.commit();
            }

            attachListeners(fragment);
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();

        IntentFilter filter = new IntentFilter();

        filter.addAction(Kuick.ACTION_DATABASE_CHANGE);

        registerReceiver(mReceiver, filter);
        reconstructGroup();
        updateCalculations();
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        unregisterReceiver(mReceiver);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.actions_transfer, menu);

        mRetryMenu = menu.findItem(R.id.actions_transfer_receiver_retry_receiving);
        mShowFilesMenu = menu.findItem(R.id.actions_transfer_receiver_show_files);
        mAddDeviceMenu = menu.findItem(R.id.actions_transfer_sender_add_device);
        mLimitMenu = menu.findItem(R.id.actions_transfer_limit_to);
        mToggleBrowserShare = menu.findItem(R.id.actions_transfer_toggle_browser_share);

        showMenus();

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu)
    {
        int devicePosition = findCurrentDevicePosition();
        Menu thisMenu = menu.findItem(R.id.actions_transfer_limit_to).getSubMenu();

        MenuItem checkedItem = null;

        if ((devicePosition < 0 || (checkedItem = thisMenu.getItem(devicePosition)) == null) && thisMenu.size() > 0)
            checkedItem = thisMenu.getItem(thisMenu.size() - 1);

        if (checkedItem != null)
            checkedItem.setChecked(true);

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.actions_transfer_remove) {
            DialogUtils.showRemoveDialog(this, mTransfer);
        } else if (id == R.id.actions_transfer_receiver_retry_receiving) {
            Transfers.recoverIncomingInterruptions(TransferDetailActivity.this, mTransfer.id);
            createSnackbar(R.string.mesg_retryReceivingNotice).show();
        } else if (id == R.id.actions_transfer_receiver_show_files) {
            startActivity(new Intent(this, FileExplorerActivity.class)
                    .putExtra(FileExplorerActivity.EXTRA_FILE_PATH,
                            FileUtils.getSavePath(this, mTransfer).getUri()));
        } else if (id == R.id.actions_transfer_sender_add_device) {
            startDeviceAddingActivity();
        } else if (item.getItemId() == R.id.actions_transfer_toggle_browser_share) {
            mTransfer.isServedOnWeb = !mTransfer.isServedOnWeb;
            getDatabase().update(mTransfer);
            getDatabase().broadcast();
            showMenus();
        } else if (item.getGroupId() == R.id.actions_abs_view_transfer_activity_limit_to) {
            mMember = item.getOrder() < mIndex.members.length ? mIndex.members[item.getOrder()] : null;

            TransferItemDetailExplorerFragment fragment = (TransferItemDetailExplorerFragment)
                    getSupportFragmentManager()
                            .findFragmentById(R.id.activity_transaction_content_frame);

            if (fragment != null && fragment.getAdapter().setMember(mMember))
                fragment.refreshList();
        } else
            return super.onOptionsItemSelected(item);

        return true;
    }

    @Override
    public void onBackPressed()
    {
        if (mBackPressedListener == null || !mBackPressedListener.onBackPressed())
            super.onBackPressed();
    }

    private void attachListeners(Fragment initiatedItem)
    {
        mBackPressedListener = initiatedItem instanceof OnBackPressedListener ? (OnBackPressedListener) initiatedItem
                : null;
    }

    @Override
    public Snackbar createSnackbar(int resId, Object... objects)
    {
        TransferItemDetailExplorerFragment explorerFragment = (TransferItemDetailExplorerFragment) getSupportFragmentManager()
                .findFragmentById(R.id.activity_transaction_content_frame);

        if (explorerFragment != null && explorerFragment.isAdded())
            return explorerFragment.createSnackbar(resId, objects);

        return Snackbar.make(findViewById(R.id.activity_transaction_content_frame), getString(resId, objects),
                Snackbar.LENGTH_LONG);
    }

    public int findCurrentDevicePosition()
    {
        LoadedMember[] members = mIndex.members;

        if (mMember != null && members.length > 0) {
            for (int i = 0; i < members.length; i++) {
                LoadedMember member = members[i];

                if (mMember.deviceId.equals(member.device.uid))
                    return i;
            }
        }

        return -1;
    }

    public LoadedMember getMember()
    {
        return mMember;
    }

    public TransferItemDetailExplorerFragment getExplorerFragment()
    {
        return (TransferItemDetailExplorerFragment) getSupportFragmentManager().findFragmentById(
                R.id.activity_transaction_content_frame);
    }

    @Override
    public Identity getIdentity()
    {
        return FileTransferTask.identifyWith(mTransfer.id);
    }

    @Nullable
    public ExtendedFloatingActionButton getToggleButton()
    {
        TransferItemDetailExplorerFragment explorerFragment = getExplorerFragment();
        return explorerFragment != null ? explorerFragment.getToggleButton() : null;
    }

    public boolean isDeviceRunning(String deviceId)
    {
        return hasTaskWith(FileTransferTask.identifyWith(mTransfer.id, deviceId));
    }

    public void reconstructGroup()
    {
        try {
            getDatabase().reconstruct(mTransfer);
        } catch (Exception e) {
            e.printStackTrace();
            finish();
        }
    }

    private void setTransfer(Transfer transfer)
    {
        mTransfer = transfer;
        mIndex = new IndexOfTransferGroup(transfer);
    }

    public void showMenus()
    {
        boolean hasRunning = hasTaskOf(FileTransferTask.class);
        boolean hasAnyFiles = mIndex.numberOfTotal() > 0;
        boolean hasIncoming = mIndex.hasIncoming();
        boolean hasOutgoing = mIndex.hasOutgoing();
        ExtendedFloatingActionButton toggleButton = getToggleButton();

        if (mRetryMenu == null || mShowFilesMenu == null)
            return;

        if (toggleButton != null) {
            if (Build.VERSION.SDK_INT <= 14 || !toggleButton.hasOnClickListeners())
                toggleButton.setOnClickListener(v -> toggleTask());

            if (hasAnyFiles || hasRunning) {
                toggleButton.setIconResource(hasRunning ? R.drawable.ic_pause_white_24dp
                        : R.drawable.ic_play_arrow_white_24dp);
                toggleButton.setBackgroundTintList(ColorStateList.valueOf(hasRunning ? mColorActive : mColorNormal));

                if (hasRunning)
                    toggleButton.setText(R.string.butn_pause);
                else
                    toggleButton.setText(hasIncoming == hasOutgoing ? R.string.butn_start
                            : (hasIncoming ? R.string.butn_receive : R.string.butn_send));

                toggleButton.setVisibility(View.VISIBLE);
            } else
                toggleButton.setVisibility(View.GONE);
        }

        mToggleBrowserShare.setTitle(mTransfer.isServedOnWeb ? R.string.butn_hideOnBrowser : R.string.butn_shareOnBrowser);
        mToggleBrowserShare.setVisible(hasOutgoing || mTransfer.isServedOnWeb);
        mAddDeviceMenu.setVisible(hasOutgoing);
        mRetryMenu.setVisible(hasIncoming);
        mShowFilesMenu.setVisible(hasIncoming);

        if (hasOutgoing && (mIndex.members.length > 0 || mMember != null)) {
            Menu dynamicMenu = mLimitMenu.setVisible(true).getSubMenu();
            dynamicMenu.clear();

            int i = 0;
            LoadedMember[] members = mIndex.members;

            if (members.length > 0)
                for (; i < members.length; i++) {
                    LoadedMember member = members[i];

                    dynamicMenu.add(R.id.actions_abs_view_transfer_activity_limit_to, 0, i,
                            member.device.username);
                }

            dynamicMenu.add(R.id.actions_abs_view_transfer_activity_limit_to, 0, i, R.string.text_none);
            dynamicMenu.setGroupCheckable(R.id.actions_abs_view_transfer_activity_limit_to, true,
                    true);
        } else
            mLimitMenu.setVisible(false);

        setTitle(getResources().getQuantityString(R.plurals.text_files, mIndex.numberOfTotal(),
                mIndex.numberOfTotal()));
    }

    public void startDeviceAddingActivity()
    {
        startActivityForResult(new Intent(this, AddDevicesToTransferActivity.class)
                .putExtra(AddDevicesToTransferActivity.EXTRA_TRANSFER, mTransfer), REQUEST_ADD_DEVICES);
    }

    public static void startInstance(Context context, Transfer transfer)
    {
        context.startActivity(new Intent(context, TransferDetailActivity.class)
                .setAction(ACTION_LIST_TRANSFERS)
                .putExtra(EXTRA_TRANSFER, transfer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void toggleTask()
    {
        List<LoadedMember> memberList = Transfers.loadMemberList(this, mTransfer.id, null);

        if (memberList.size() > 0) {
            if (memberList.size() == 1) {
                LoadedMember member = memberList.get(0);
                toggleTaskForMember(member);
            } else
                new ToggleMultipleTransferDialog(TransferDetailActivity.this, mIndex).show();
        } else if (mIndex.hasOutgoing())
            startDeviceAddingActivity();
    }

    public void toggleTaskForMember(final LoadedMember member)
    {
        if (hasTaskWith(FileTransferTask.identifyWith(mTransfer.id, member.deviceId)))
            Transfers.pauseTransfer(this, member);
        else {
            try {
                Transfers.getAddressListFor(getDatabase(), member.deviceId);
                Transfers.startTransferWithTest(this, mTransfer, member);
            } catch (ConnectionNotFoundException e) {
                createSnackbar(R.string.mesg_transferConnectionNotSetUpFix).show();
            }
        }
    }

    public synchronized void updateCalculations()
    {
        if (mDataCruncher == null || !mDataCruncher.requestRestart()) {
            mDataCruncher = new CrunchLatestDataTask(() -> {
                showMenus();
                findViewById(R.id.activity_transaction_no_devices_warning).setVisibility(
                        mIndex.members.length > 0 ? View.GONE : View.VISIBLE);

                if (mIndex.members.length == 0)
                    if (mTransferItem != null) {
                        new TransferInfoDialog(TransferDetailActivity.this, mIndex,
                                mTransferItem, mMember == null ? null : mMember.deviceId).show();
                        mTransferItem = null;
                    }
            });

            mDataCruncher.execute(this);
        }
    }

    @Override
    public void onTaskStateChanged(BaseAttachableAsyncTask task)
    {
        if (task instanceof FileTransferTask)
            ((FileTransferTask) task).setAnchor(this);
    }

    @Override
    public boolean onTaskMessage(TaskMessage message)
    {
        return false;
    }

    public static class CrunchLatestDataTask extends AsyncTask<TransferDetailActivity, Void, Void>
    {
        private final PostExecutionListener mListener;
        private boolean mRestartRequested = false;

        public CrunchLatestDataTask(PostExecutionListener listener)
        {
            mListener = listener;
        }

        /* "possibility of having more than one ViewTransferActivity" < "sun turning into black hole" */
        @Override
        protected Void doInBackground(TransferDetailActivity... activities)
        {
            do {
                mRestartRequested = false;

                for (TransferDetailActivity activity : activities)
                    if (activity.mTransfer != null)
                        Transfers.loadGroupInfo(activity, activity.mIndex, activity.getMember());
            } while (mRestartRequested && !isCancelled());

            return null;
        }

        public boolean requestRestart()
        {
            if (getStatus().equals(Status.RUNNING))
                mRestartRequested = true;

            return mRestartRequested;
        }

        @Override
        protected void onPostExecute(Void aVoid)
        {
            super.onPostExecute(aVoid);
            mListener.onPostExecute();
        }

        /* Should we have used a generic type class for this?
         * This interface aims to keep its parent class non-anonymous
         */
        public interface PostExecutionListener
        {
            void onPostExecute();
        }
    }
}
