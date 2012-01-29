/*
 * Copyright (c) 2009-2010 David Grant
 * Copyright (c) 2010 ThruPoint Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jscep.transaction;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.CertStore;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.logging.Logger;

import org.bouncycastle.asn1.pkcs.CertificationRequest;
import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.jscep.asn1.IssuerAndSubject;
import org.jscep.content.CertRepContentHandler;
import org.jscep.message.CertRep;
import org.jscep.message.GetCertInitial;
import org.jscep.message.PkiMessage;
import org.jscep.message.PkiMessageDecoder;
import org.jscep.message.PkiMessageEncoder;
import org.jscep.request.PKCSReq;
import org.jscep.transaction.Transaction.State;
import org.jscep.transport.Transport;
import org.jscep.util.LoggingUtil;
import org.jscep.x509.X509Util;


/**
 * This class represents a SCEP transaction, and provides a framework for 
 * performing operations.
 * <p>
 * The behaviour of this class changes in accordance with the possible valid states
 * for each transaction operation.  For enrolment operations, clients should inspect 
 * the {@link State} returned by the {@link #send()} or {@link #poll()}.
 * 
 * @author David Grant
 */
public class EnrolmentTransaction extends Transaction {
	private final TransactionId transId;
	private final org.jscep.message.PKCSReq request;
	private static NonceQueue QUEUE = new NonceQueue(20);
	private static Logger LOGGER = LoggingUtil.getLogger(EnrolmentTransaction.class);
	private X509Certificate issuer;

	public EnrolmentTransaction(Transport transport, PkiMessageEncoder encoder, PkiMessageDecoder decoder, CertificationRequest csr) throws IOException {
		super(transport, encoder, decoder);
		this.transId = TransactionId.createTransactionId(X509Util.getPublicKey(csr), "SHA-1");
		this.request = new org.jscep.message.PKCSReq(transId, Nonce.nextNonce(), csr);
	}
	
	@Override
	public TransactionId getId() {
		return transId;
	}
	
	/**
	 * Performs a certificate enrolment for the CSR given in the constructor.
	 * 
	 * @return the resulting transaction state.
	 * @throws IOException if any I/O error occurs.
	 */
	public State send() throws IOException {
		CMSSignedData signedData = encoder.encode(request);
		CertRepContentHandler handler = new CertRepContentHandler();
		final CMSSignedData res = transport.sendRequest(new PKCSReq(signedData, handler));

		CertRep response = (CertRep) decoder.decode(res);
		validateExchange(request, response);
		
		if (response.getPkiStatus() == PkiStatus.FAILURE) {
			failInfo = response.getFailInfo();
			state = State.CERT_NON_EXISTANT;
		} else if (response.getPkiStatus() == PkiStatus.SUCCESS) {
			certStore = extractCertStore(response);
			state = State.CERT_ISSUED;
		} else {
			state = State.CERT_REQ_PENDING;
		}
		
		return state;
	}
	
	/**
	 * Polls the SCEP server for an update on the enrolment operation.
	 * 
	 * @return the resulting transaction state.
	 * @throws IOException if any I/O error occurs.
	 */
	public State poll() throws IOException {
		X509Name issuerName = X509Util.toX509Name(issuer.getSubjectX500Principal());
		X509Name subjectName = request.getMessageData().getCertificationRequestInfo().getSubject();
		IssuerAndSubject ias = new IssuerAndSubject(issuerName, subjectName);
		final GetCertInitial pollReq = new GetCertInitial(transId, Nonce.nextNonce(), ias);
		CMSSignedData signedData = encoder.encode(pollReq);
		CertRepContentHandler handler = new CertRepContentHandler();
		final CMSSignedData res = transport.sendRequest(new PKCSReq(signedData, handler));
		
		CertRep response = (CertRep) decoder.decode(res);
		validateExchange(pollReq, response);
		
		if (response.getPkiStatus() == PkiStatus.FAILURE) {
			failInfo = response.getFailInfo();
			state = State.CERT_NON_EXISTANT;
		} else if (response.getPkiStatus() == PkiStatus.SUCCESS) {
			certStore = extractCertStore(response);
			state = State.CERT_ISSUED;
		} else {
			state = State.CERT_REQ_PENDING;
		}
		
		return state;
	}

	private CertStore extractCertStore(CertRep response) throws IOException {
		CertStore cs = null;
		try {
			CMSSignedData signedData = response.getCMSSignedData();
			cs = signedData.getCertificatesAndCRLs("Collection", (String) null);
		} catch (GeneralSecurityException e) {
			IOException ioe = new IOException(e);
			
			LOGGER.throwing(getClass().getName(), "getContent", ioe);
			throw ioe;
		} catch (CMSException e) {
			IOException ioe = new IOException(e);
			
			LOGGER.throwing(getClass().getName(), "getContent", ioe);
			throw ioe;
		}
		return cs;
	}

	private void validateExchange(PkiMessage<?> req, CertRep res) throws IOException {
		if (res.getTransactionId().equals(req.getTransactionId()) == false) {
			final IOException ioe = new IOException("Transaction ID Mismatch");
			
			LOGGER.throwing(getClass().getName(), "validateResponse", ioe);
			throw ioe;
		}

		// The requester SHOULD verify that the recipientNonce of the reply
		// matches the senderNonce it sent in the request.
		if (res.getRecipientNonce().equals(req.getSenderNonce()) == false) {
			InvalidNonceException e = new InvalidNonceException("Response recipient nonce and request sender nonce are not equal");
			
			LOGGER.throwing(getClass().getName(), "validateResponse", e);
			throw e;
		}
		
		if (res.getSenderNonce() == null) {
			return;
		}

		// http://tools.ietf.org/html/draft-nourse-scep-20#section-8.5
		// Check that the nonce has not been encountered before.
		if (QUEUE.contains(res.getSenderNonce())) {
			InvalidNonceException e = new InvalidNonceException("This nonce has been encountered before.  Possible replay attack?");
			
			LOGGER.throwing(getClass().getName(), "validateResponse", e);
			throw e;
		} else {
			QUEUE.offer(res.getSenderNonce());
		}
	}
	
	public void setIssuer(X509Certificate ca) {
		issuer = ca;
	}
}
