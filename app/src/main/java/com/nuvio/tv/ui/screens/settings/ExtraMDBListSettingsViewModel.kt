package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.MDBListApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class ExtraMDBListSettingsViewModel @Inject constructor(
    @Named("extra_mdblist") dataStore: MDBListSettingsDataStore,
    mdbListApi: MDBListApi
) : MDBListSettingsViewModel(
    dataStore = dataStore,
    mdbListApi = mdbListApi
)
