package com.badlogic.gdx.pay.android.googleplay.billing;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.vending.billing.IInAppBillingService;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidEventListener;
import com.badlogic.gdx.pay.Information;
import com.badlogic.gdx.pay.Offer;
import com.badlogic.gdx.pay.Transaction;
import com.badlogic.gdx.pay.android.googleplay.GdxPayException;
import com.badlogic.gdx.pay.android.googleplay.billing.GoogleInAppBillingService.ConnectionListener;
import com.badlogic.gdx.pay.android.googleplay.billing.converter.PurchaseResponseActivityResultConverter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.badlogic.gdx.pay.android.googleplay.AndroidGooglePlayPurchaseManager.PURCHASE_TYPE_IN_APP;
import static com.badlogic.gdx.pay.android.googleplay.ResponseCode.BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE;
import static com.badlogic.gdx.pay.android.googleplay.billing.V3GoogleInAppBillingService.BILLING_API_VERSION;
import static com.badlogic.gdx.pay.android.googleplay.billing.V3GoogleInAppBillingService.DEFAULT_DEVELOPER_PAYLOAD;
import static com.badlogic.gdx.pay.android.googleplay.testdata.GetBuyIntentResponseObjectMother.buyIntentResponseOk;
import static com.badlogic.gdx.pay.android.googleplay.testdata.GetPurchasesResponseObjectMother.purchasesResponseOneTransactionFullEdition;
import static com.badlogic.gdx.pay.android.googleplay.testdata.GetSkuDetailsResponseBundleObjectMother.skuDetailsResponseResultNetworkError;
import static com.badlogic.gdx.pay.android.googleplay.testdata.GetSkuDetailsResponseBundleObjectMother.skuDetailsResponseResultOkProductFullEditionEntitlement;
import static com.badlogic.gdx.pay.android.googleplay.testdata.InformationObjectMother.informationFullEditionEntitlement;
import static com.badlogic.gdx.pay.android.googleplay.testdata.OfferObjectMother.offerFullEditionEntitlement;
import static com.badlogic.gdx.pay.android.googleplay.testdata.PurchaseRequestActivityResultObjectMother.activityResultPurchaseFullEditionSuccess;
import static com.badlogic.gdx.pay.android.googleplay.testdata.TestConstants.PACKAGE_NAME_GOOD;
import static com.badlogic.gdx.pay.android.googleplay.testdata.TransactionObjectMother.transactionFullEditionEuroGooglePlay;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class V3GoogleInAppBillingServiceTest {

    public static final int ACTIVITY_REQUEST_CODE = 1002;
    @Mock
    AndroidApplication androidApplication;

    @Captor
    ArgumentCaptor<ServiceConnection> serviceConnectionArgumentCaptor;

    @Captor
    ArgumentCaptor<AndroidEventListener> androidEventListenerArgumentCaptor;

    @Mock
    IInAppBillingService nativeInAppBillingService;

    @Mock
    ConnectionListener connectionListener;

    @Mock
    private GoogleInAppBillingService.PurchaseRequestCallback purchaseRequestCallback;

    @Mock
    private PurchaseResponseActivityResultConverter purchaseResponseActivityResultConverter;

    private V3GoogleInAppBillingService v3InAppbillingService;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void setUp() throws Exception {
        when(androidApplication.getPackageName()).thenReturn(PACKAGE_NAME_GOOD);

        v3InAppbillingService = new V3GoogleInAppBillingService(androidApplication, ACTIVITY_REQUEST_CODE, purchaseResponseActivityResultConverter) {
            @Override
            protected IInAppBillingService lookupByStubAsInterface(IBinder binder) {
                return nativeInAppBillingService;
            }
        };
    }

    @Test
    public void installShouldStartActivityIntent() throws Exception {

        whenActivityBindReturn(true);

        requestConnect();

        verify(androidApplication).bindService(isA(Intent.class), isA(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));
    }

    @Test
    public void shouldCallObserverInstallErrorOnActivityBindFailure() throws Exception {
        whenActivityBindThrow(new SecurityException("Not allowed to bind to this service"));

        requestConnect();

        verify(connectionListener).disconnected(isA(GdxPayException.class));
    }

    @Test
    public void shouldCallConnectionListenerFailureWhenActivityBindReturnsFalse() throws Exception {
        whenActivityBindReturn(false);

        requestConnect();

        verify(connectionListener).disconnected(isA(GdxPayException.class));
    }

    @Test
    public void shouldCallConnectSuccessWhenConnectSucceeds() throws Exception {
        activityBindAndConnect();

        verify(connectionListener).connected();
    }

    @Test
    public void shouldReturnSkusWhenResponseIsOk() throws Exception {

        whenBillingServiceGetSkuDetailsReturn(skuDetailsResponseResultOkProductFullEditionEntitlement());

        activityBindAndConnect();

        Offer offer = offerFullEditionEntitlement();

        Map<String, Information> details = v3InAppbillingService.getProductsDetails(singletonList(offer.getIdentifier()));

        assertEquals(details, Collections.singletonMap(offer.getIdentifier(), informationFullEditionEntitlement()));
    }

    @Test
    public void shouldThrowExceptionWhenGetSkuDetailsResponseResultIsNetworkError() throws Exception {
        whenBillingServiceGetSkuDetailsReturn(skuDetailsResponseResultNetworkError());

        activityBindAndConnect();

        thrown.expect(GdxPayException.class);

        v3InAppbillingService.getProductsDetails(singletonList("TEST"));
    }

    @Test
    public void shouldThrowExceptionOnGetSkuDetailsWhenDisconnected() throws Exception {
        thrown.expect(GdxPayException.class);

        v3InAppbillingService.getProductsDetails(singletonList("TEST"));
    }

    @Test
    public void shouldStartSenderIntentForBuyIntentResponseOk() throws Exception {
        activityBindAndConnect();

        Offer offer = offerFullEditionEntitlement();

        whenGetBuyIntentForIdentifierReturn(offer.getIdentifier(), buyIntentResponseOk());

        v3InAppbillingService.startPurchaseRequest(offer.getIdentifier(), purchaseRequestCallback);

        verify(androidApplication).startIntentSenderForResult(isA(IntentSender.class),
                eq(ACTIVITY_REQUEST_CODE), isA(Intent.class), eq(0), eq(0), eq(0));
    }

    @Test
    public void shouldCallGdxPurchaseCallbackErrorAndReconnectWhenGetBuyIntentFailsWithDeadObjectException() throws Exception {
        activityBindAndConnect();

        Offer offer = offerFullEditionEntitlement();

        whenGetBuyIntentForIdentifierThrow(offer.getIdentifier(), new DeadObjectException("Purchase service died."));

        v3InAppbillingService.startPurchaseRequest(offer.getIdentifier(), purchaseRequestCallback);

        verify(purchaseRequestCallback).purchaseError(isA(GdxPayException.class));

        verify(androidApplication).unbindService(isA(ServiceConnection.class));

        verifyAndroidApplicationBindService(2);
    }

    @Test
    public void shouldCallPurchaseCallbackErrorWhenPurcharseIntentSenderForResultFails() throws Exception {
        activityBindAndConnect();

        Offer offer = offerFullEditionEntitlement();

        whenGetBuyIntentForIdentifierReturn(offer.getIdentifier(), buyIntentResponseOk());

        doThrow(new IntentSender.SendIntentException("Intent cancelled")).when(androidApplication)
                .startIntentSenderForResult(isA(IntentSender.class),
                        eq(ACTIVITY_REQUEST_CODE), isA(Intent.class), eq(0), eq(0), eq(0));
        v3InAppbillingService.startPurchaseRequest(offer.getIdentifier(), purchaseRequestCallback);

        verify(androidApplication).startIntentSenderForResult(isA(IntentSender.class),
                eq(ACTIVITY_REQUEST_CODE), isA(Intent.class), eq(0), eq(0), eq(0));

        verify(purchaseRequestCallback).purchaseError(isA(GdxPayException.class));
    }

    @Test
    public void shouldCallPurchaseListenerOnActivityResultAfterSuccessfulPurchaseRequest() throws Exception {
        Offer offer = offerFullEditionEntitlement();

        bindConnectAndStartPurchaseRequest(offer);

        AndroidEventListener eventListener = captureAndroidEventListener();

        whenBillingServiceGetSkuDetailsReturn(skuDetailsResponseResultOkProductFullEditionEntitlement());

        when(purchaseResponseActivityResultConverter.convertToTransaction(isA(Intent.class)))
                .thenReturn(transactionFullEditionEuroGooglePlay());

        eventListener.onActivityResult(ACTIVITY_REQUEST_CODE, Activity.RESULT_OK, activityResultPurchaseFullEditionSuccess());

        verify(purchaseRequestCallback).purchaseSuccess(isA(Transaction.class));
    }

    @Test
    public void shouldCallPurchaseErrorIfConvertingIntentDataToTransactionFails() throws Exception {
        bindConnectAndStartPurchaseRequest(offerFullEditionEntitlement());

        AndroidEventListener eventListener = captureAndroidEventListener();

        when(purchaseResponseActivityResultConverter.convertToTransaction(isA(Intent.class)))
                .thenThrow(new GdxPayException("Exception parsing Json"));

        eventListener.onActivityResult(ACTIVITY_REQUEST_CODE, Activity.RESULT_OK, activityResultPurchaseFullEditionSuccess());

        verify(purchaseRequestCallback).purchaseError(isA(GdxPayException.class));
    }


    @Test
    public void shouldCallPurchaseErrorIfResultIsError() throws Exception {
        bindConnectAndStartPurchaseRequest(offerFullEditionEntitlement());

        AndroidEventListener eventListener = captureAndroidEventListener();

        eventListener.onActivityResult(ACTIVITY_REQUEST_CODE, BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE.getCode(), activityResultPurchaseFullEditionSuccess());

        verify(purchaseRequestCallback).purchaseError(isA(GdxPayException.class));

        verifyNoMoreInteractions(purchaseResponseActivityResultConverter);
    }

    @Test
    public void shouldCallPurchaseCanceledOnResultCodeZero() throws Exception {
        Offer offer = offerFullEditionEntitlement();

        bindConnectAndStartPurchaseRequest(offer);

        AndroidEventListener eventListener = captureAndroidEventListener();

        whenBillingServiceGetSkuDetailsReturn(skuDetailsResponseResultOkProductFullEditionEntitlement());

        when(purchaseResponseActivityResultConverter.convertToTransaction(isA(Intent.class)))
                .thenReturn(transactionFullEditionEuroGooglePlay());

        eventListener.onActivityResult(ACTIVITY_REQUEST_CODE, Activity.RESULT_CANCELED, new Intent());

        verify(purchaseRequestCallback).purchaseCanceled();
    }

    @Test
    public void getPurchasesWithResultOkShouldReturnPurchaseTransactions() throws Exception {
        activityBindAndConnect();

        whenGetPurchasesRequestReturn(purchasesResponseOneTransactionFullEdition());

        List<Transaction> transactions = v3InAppbillingService.getPurchases();

        verify(nativeInAppBillingService).getPurchases(BILLING_API_VERSION, PACKAGE_NAME_GOOD, V3GoogleInAppBillingService.PURCHASE_TYPE_IN_APP, null);

        assertEquals(1, transactions.size());

        assertEquals(offerFullEditionEntitlement().getIdentifier(), transactions.get(0).getIdentifier());
    }

    @Test
    public void shouldThrowGdxPayExceptionWhenGetPurchasesFails() throws Exception {
        activityBindAndConnect();

        thrown.expect(GdxPayException.class);

        whenGetPurchasesRequestThrow(new DeadObjectException("Disconnected"));

        v3InAppbillingService.getPurchases();
    }

    @Test
    public void onServiceDisconnectedShouldDisconnectService() throws Exception {
        ServiceConnection connection = bindAndFetchNewConnection();

        connection.onServiceConnected(null, null);

        assertTrue(v3InAppbillingService.isConnected());

        connection.onServiceDisconnected(null);

        assertFalse(v3InAppbillingService.isConnected());
    }

    @Test
    public void connectingTwiceInARowShouldBeBlocked() throws Exception {
        ServiceConnection connection = bindAndFetchNewConnection();

        connection.onServiceConnected(null, null);
        connection.onServiceConnected(null, null);

        verify(connectionListener, times(1)).connected();
    }


    @Test
    public void disconnectShouldDisconnectFromActivity() throws Exception {
        ServiceConnection connection = bindAndFetchNewConnection();

        connection.onServiceConnected(null, null);

        v3InAppbillingService.disconnect();

        verify(androidApplication).removeAndroidEventListener(isA(AndroidEventListener.class));
        verify(androidApplication).unbindService(connection);

        assertFalse(v3InAppbillingService.isConnected());
    }

    @Test
    public void calculatesDeltaCorrectly() throws Exception {
        int actualDelta= v3InAppbillingService.deltaInSeconds(10_001, 5_000);

        assertEquals(5, actualDelta);
    }

    private void whenGetPurchasesRequestThrow(Exception exception) {
        try {
            when(nativeInAppBillingService.getPurchases(BILLING_API_VERSION, PACKAGE_NAME_GOOD, PURCHASE_TYPE_IN_APP, null))
                    .thenThrow(exception);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private void whenGetPurchasesRequestReturn(Bundle response) {
        try {
            when(nativeInAppBillingService.getPurchases(BILLING_API_VERSION, PACKAGE_NAME_GOOD, PURCHASE_TYPE_IN_APP, null)).thenReturn(response);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private AndroidEventListener captureAndroidEventListener() {
        verify(androidApplication).addAndroidEventListener(androidEventListenerArgumentCaptor.capture());
        return androidEventListenerArgumentCaptor.getValue();
    }

    private void bindConnectAndStartPurchaseRequest(Offer offer) throws android.os.RemoteException {
        activityBindAndConnect();


        whenGetBuyIntentForIdentifierReturn(offer.getIdentifier(), buyIntentResponseOk());

        v3InAppbillingService.startPurchaseRequest(offer.getIdentifier(), purchaseRequestCallback);
    }

    private void whenGetBuyIntentForIdentifierReturn(String productId, Bundle buyIntentResponse) throws android.os.RemoteException {
        when(nativeInAppBillingService.getBuyIntent(BILLING_API_VERSION, PACKAGE_NAME_GOOD, productId,
                V3GoogleInAppBillingService.PURCHASE_TYPE_IN_APP, DEFAULT_DEVELOPER_PAYLOAD))
                .thenReturn(buyIntentResponse);
    }

    private void whenGetBuyIntentForIdentifierThrow(String productId, RemoteException exception) throws RemoteException {
        when(nativeInAppBillingService.getBuyIntent(BILLING_API_VERSION, PACKAGE_NAME_GOOD, productId,
                V3GoogleInAppBillingService.PURCHASE_TYPE_IN_APP, DEFAULT_DEVELOPER_PAYLOAD))
                .thenThrow(exception);
    }

    private void activityBindAndConnect() {
        ServiceConnection connection = bindAndFetchNewConnection();

        connection.onServiceConnected(null, null);
    }

    private void whenBillingServiceGetSkuDetailsReturn(Bundle skuDetailsResponse) throws android.os.RemoteException {
        when(nativeInAppBillingService.getSkuDetails(
                        eq(BILLING_API_VERSION),
                        isA(String.class),
                        eq(PURCHASE_TYPE_IN_APP),
                        isA(Bundle.class))
        ).thenReturn(skuDetailsResponse);
    }

    private ServiceConnection bindAndFetchNewConnection() {
        whenActivityBindReturn(true);

        requestConnect();

        verifyAndroidApplicationBindService(1);

        return serviceConnectionArgumentCaptor.getValue();
    }

    private void verifyAndroidApplicationBindService(int times) {
        verify(androidApplication, times(times)).bindService(isA(Intent.class), serviceConnectionArgumentCaptor.capture(), eq(Context.BIND_AUTO_CREATE));
    }

    private void whenActivityBindThrow(SecurityException exception) {
        when(androidApplication.bindService(isA(Intent.class), isA(ServiceConnection.class),
                eq(Context.BIND_AUTO_CREATE)))
                .thenThrow(exception);
    }


    private void whenActivityBindReturn(boolean returnValue) {
        when(androidApplication.bindService(isA(Intent.class), isA(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE))).thenReturn(returnValue);
    }

    private void requestConnect() {
        v3InAppbillingService.requestConnect(connectionListener);
    }
}