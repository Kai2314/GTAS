/*
 * All GTAS code is Copyright 2016, The Department of Homeland Security (DHS), U.S. Customs and Border Protection (CBP).
 * 
 * Please see LICENSE.txt for details.
 */
package gov.gtas.parsers.pnrgov;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.gtas.parsers.edifact.EdifactLexer;
import gov.gtas.parsers.exception.ParseException;
import gov.gtas.parsers.pnrgov.segment.ADD;
import gov.gtas.parsers.pnrgov.segment.SSR;
import gov.gtas.parsers.pnrgov.segment.TIF;
import gov.gtas.parsers.pnrgov.segment.TIF.TravelerDetails;
import gov.gtas.parsers.util.DateUtils;
import gov.gtas.parsers.util.ParseUtils;
import gov.gtas.parsers.vo.AddressVo;
import gov.gtas.parsers.vo.DocumentVo;
import gov.gtas.parsers.vo.PassengerVo;
import gov.gtas.parsers.vo.PhoneVo;

public class PnrUtils {
	private static final Logger logger = LoggerFactory.getLogger(PnrUtils.class);

	public static Date parseDateTime(String dt) {
		final String DATE_ONLY_FORMAT = DateUtils.DATE_FORMAT_DAY_FIRST;
		final String DATE_TIME_FORMAT = DateUtils.DT_FORMAT_DAY_FIRST;
		final String DATE_TIME_FORMAT_SEC = DateUtils.DT_FORMAT_DAY_FIRST_SEC;

		if (dt.length() == DATE_ONLY_FORMAT.length()) {
			return ParseUtils.parseDateTime(dt, DATE_ONLY_FORMAT);
		} else if (dt.length() == DATE_TIME_FORMAT.length()) {
			return ParseUtils.parseDateTime(dt, DATE_TIME_FORMAT);
		} else if (dt.length() == DATE_TIME_FORMAT_SEC.length()) {
			return ParseUtils.parseDateTime(dt, DATE_TIME_FORMAT_SEC);
		}

		return null;
	}

	/** date format used for passport/visa expiration, issuance date */
	private static final String DOC_DATE_FORMAT = "ddMMMyy";

	/**
	 * <p>
	 * We use the SSR (DOCS) segment to grab the majority of the information
	 * about the passenger -- name, document number, etc. The TIF segment is
	 * only used to record the traveler reference number and any suffix or title
	 * in the name.
	 * <p>
	 * Group 2 in a PNR (which begins with a TIF segment) may contain multiple
	 * SSR DOCS segments. This can occur for several reasons:
	 * <ol>
	 * <li>For a passenger holding a passport plus an alien card and traveling
	 * with an infant it will be necessary to provide multiple DOCS, all
	 * associated to the same passenger.
	 * <li>One of the lines exceeds the max character limit of 69 chars.
	 * </ol>
	 * <p>
	 * TODO: was not handling the 4th example below b/c of extra field. check
	 * whether it's an error in the message or not.
	 * 
	 * <p>
	 * Refer to Section "3.13.1 API — Passenger Travel Document Information" in
	 * "Reservations Interline Message Procedures — Passenger (AIRIMP)"
	 * 
	 * <pre>
	 * Examples: handle
	 * /P/GBR/123456789/GBR/12JUL64/M/23AUG19/SMITHJR/JONATHON/ROBERT
	 * //P/USA/554416148/USA/06MAY02/F/27SEP21/ROBERTS/ELIZABETH/ANNE'
	 * /////05MAY02/F//ROBERTS/ELIZABETH-1ROBERTS/ELIZABETH'
	 * 
	 * Not handle:
	 * / /   /         /   /GBR/12JUL64/M//JONES/WILLIAMNEVELL
	 * </pre>
	 */

	public static PassengerVo createPassenger(List<SSR> ssrDocs, TIF tif) throws ParseException {
		SSR bestSsr = null;
		for (SSR ssr : ssrDocs) {
			String ssrText = ssr.getFreeText();
			if (ssrText == null) {
				continue;
			} else if (bestSsr == null || ssrText.length() > bestSsr.getFreeText().length()) {
				bestSsr = ssr;
			}
		}

		if (bestSsr == null) {
			return null;
		}

		List<String> strs = splitSsrFreeText(bestSsr);
		if (CollectionUtils.isEmpty(strs)) {
			return null;
		}

		PassengerVo p = new PassengerVo();
		p.setPassengerType("P");
		DocumentVo doc = new DocumentVo();
		if (StringUtils.isNotEmpty(safeGet(strs, 1))
				|| (StringUtils.isEmpty(safeGet(strs, 1)) && StringUtils.isEmpty(safeGet(strs, 2)))) {
			doc.setDocumentType(safeGet(strs, 1));
			doc.setIssuanceCountry(safeGet(strs, 2));
			doc.setDocumentNumber(safeGet(strs, 3));
			p.setCitizenshipCountry(safeGet(strs, 4));
			String d = safeGet(strs, 5);
			if (StringUtils.isNotBlank(d)) {
				Date dob = ParseUtils.parseDateTime(d, DOC_DATE_FORMAT);
				p.setDob(dob);
				p.setAge(DateUtils.calculateAge(dob));
			}
			p.setGender(safeGet(strs, 6));
			d = safeGet(strs, 7);
			if (StringUtils.isNotBlank(d)) {
				doc.setExpirationDate(ParseUtils.parseDateTime(d, DOC_DATE_FORMAT));
			}

			processNames(p, safeGet(strs, 8), safeGet(strs, 9), safeGet(strs, 10), p.getGender());
		} else if (StringUtils.isEmpty(safeGet(strs, 1)) && StringUtils.isNotEmpty(safeGet(strs, 2))
				&& StringUtils.isNotEmpty(safeGet(strs, 4))) {
			doc.setDocumentType(safeGet(strs, 2));
			doc.setIssuanceCountry(safeGet(strs, 3));
			doc.setDocumentNumber(safeGet(strs, 4));
			p.setCitizenshipCountry(safeGet(strs, 5));
			String d = safeGet(strs, 6);
			if (StringUtils.isNotBlank(d)) {
				Date dob = ParseUtils.parseDateTime(d, DOC_DATE_FORMAT);
				p.setDob(dob);
				p.setAge(DateUtils.calculateAge(dob));
			}
			p.setGender(safeGet(strs, 7));
			d = safeGet(strs, 8);
			if (StringUtils.isNotBlank(d)) {
				doc.setExpirationDate(ParseUtils.parseDateTime(d, DOC_DATE_FORMAT));
			}

			processNames(p, safeGet(strs, 9), safeGet(strs, 10), safeGet(strs, 11), p.getGender());
		}
		else if(StringUtils.isEmpty(safeGet(strs, 1)) && StringUtils.isEmpty(safeGet(strs, 4))){
			//USA/497994674//17MAR47/M/02NOV16/ELMORE/ERVINMR/DARIN

			doc.setDocumentType(safeGet(strs, 1));
			doc.setIssuanceCountry(safeGet(strs, 2));
			doc.setDocumentNumber(safeGet(strs, 3));
			p.setCitizenshipCountry(safeGet(strs, 4));
			String d = safeGet(strs, 5);
			if (StringUtils.isNotBlank(d)) {
				Date dob = ParseUtils.parseDateTime(d, DOC_DATE_FORMAT);
				p.setDob(dob);
				p.setAge(DateUtils.calculateAge(dob));
			}
			p.setGender(safeGet(strs, 6));
			d = safeGet(strs, 7);
			if (StringUtils.isNotBlank(d)) {
				doc.setExpirationDate(ParseUtils.parseDateTime(d, DOC_DATE_FORMAT));
			}

			processNames(p, safeGet(strs, 8), safeGet(strs, 9), safeGet(strs, 10), p.getGender());
		}
		captureMissingInfoFromSSRs(p,ssrDocs);
			
		
		if (StringUtils.isNotBlank(doc.getDocumentType()) && StringUtils.isNotBlank(doc.getDocumentNumber())) {
			// FIX for issue #316
			if (StringUtils.isBlank(p.getCitizenshipCountry())) {
				p.setCitizenshipCountry(doc.getIssuanceCountry());
			}
			p.addDocument(doc);
		} else {
			// ToDo: logger invalid document
		}

		if (!tif.getTravelerDetails().isEmpty()) {
			TravelerDetails td = tif.getTravelerDetails().get(0);
			p.setTravelerReferenceNumber(td.getTravelerReferenceNumber());

			PassengerVo tmp = new PassengerVo();
			if (tif.getTravelerSurname() != null && td.getTravelerGivenName() != null)
				processNames(tmp, tif.getTravelerSurname(), td.getTravelerGivenName(), null, p.getGender());
			if (StringUtils.isNoneBlank(tif.getTravelerSurname()) && StringUtils.isBlank(p.getLastName())) {
				p.setLastName(tmp.getLastName());
			}
			if (StringUtils.isNoneBlank(td.getTravelerGivenName()) && StringUtils.isBlank(p.getFirstName())) {
				p.setFirstName(tmp.getFirstName());
			}
			checkNamesForExtraChars(p);
	
			p.setTitle(tmp.getTitle());
			p.setSuffix(tmp.getSuffix());
		}

		return p;
	}


	//Handling names with a 1 #478 code fix
	private static void checkNamesForExtraChars(PassengerVo p){
		if(StringUtils.isNotBlank(p.getMiddleName()) && p.getMiddleName().indexOf("-1") != -1){
			p.setMiddleName(p.getMiddleName().substring(0,p.getMiddleName().indexOf("-1")));
		}
		if(StringUtils.isNotBlank(p.getLastName()) && p.getLastName().indexOf("-1") != -1){
			p.setLastName(p.getLastName().substring(0,p.getLastName().indexOf("-1")));
		}
		if(StringUtils.isNotBlank(p.getFirstName()) && p.getFirstName().indexOf("-1") != -1){
			p.setFirstName(p.getFirstName().substring(0,p.getFirstName().indexOf("-1")));
		}
	}
	public static DocumentVo createVisa(SSR ssr) {
		List<String> strs = splitSsrFreeText(ssr);
		if (CollectionUtils.isEmpty(strs)) {
			return null;
		}

		DocumentVo visa = new DocumentVo();
		// index 1 place of birth
		visa.setDocumentType(safeGet(strs, 2));
		visa.setDocumentNumber(safeGet(strs, 3));
		// index 4 city of issue
		String d = safeGet(strs, 5);
		if (StringUtils.isNotBlank(d)) {
			Date issuanceDate = ParseUtils.parseDateTime(d, DOC_DATE_FORMAT);
			visa.setIssuanceDate(issuanceDate);
		}
		visa.setIssuanceCountry(safeGet(strs, 6));

		return visa;
	}

	public static AddressVo createAddress(ADD add) {
		AddressVo rv = new AddressVo();
		rv.setType(add.getAddressType());
		rv.setLine1(add.getStreetNumberAndName());
		rv.setCity(add.getCity());
		rv.setState(add.getStateOrProvinceCode());
		rv.setCountry(add.getCountryCode());
		rv.setPostalCode(add.getPostalCode());
		rv.setPhoneNumber(ParseUtils.prepTelephoneNumber(add.getTelephone()));
		rv.setEmail(add.getEmail());
		return rv;
	}

	/**
	 * SSR+DOCA:HK:1:TZ:::::/D/AUS/13 SHORE AVENUE/BROADBEACH/QLD/4215+::43577'
	 */
	public static AddressVo createAddress(SSR ssr) {
		List<String> strs = splitSsrFreeText(ssr);
		if (strs == null) {
			return null;
		}

		AddressVo rv = new AddressVo();
		rv.setCountry(safeGet(strs, 2));
		rv.setLine1(safeGet(strs, 3));
		rv.setCity(safeGet(strs, 4));
		rv.setState(safeGet(strs, 5));
		rv.setPostalCode(safeGet(strs, 6));
		return rv;
	}

	public static PhoneVo createPhone(String number) {
		PhoneVo rv = new PhoneVo();
		rv.setNumber(ParseUtils.prepTelephoneNumber(number));
		return rv;
	}

	/**
	 * Extract the nth PNR from the msg text.
	 * 
	 * @param msg
	 *            the entire msg text, including UNA, if it exists
	 * @param index
	 *            0-based index of the PNR
	 * @return text of the nth PNR; null if does not exist.
	 */
	public static String getSinglePnr(EdifactLexer lexer, int index) {
		String regex = String.format("SRC\\s*\\%c", lexer.getUna().getSegmentTerminator());
		Pattern pattern = Pattern.compile(regex);
		Matcher matcher = pattern.matcher(lexer.getMessage());
		boolean found = false;
		for (int i = 0; i <= index; i++) {
			found = matcher.find();
		}

		if (!found) {
			return null;
		}
		int start = matcher.start();

		int end = -1;
		if (matcher.find()) {
			end = matcher.start();
		} else {
			end = lexer.getStartOfSegment("UNT");
		}

		if (end != -1) {
			return lexer.getMessage().substring(start, end);
		} else {
			return lexer.getMessage().substring(start);
		}
	}

	public static List<String> getPnrs(String msg) {
		if (StringUtils.isBlank(msg)) {
			return null;
		}
		EdifactLexer lexer = new EdifactLexer(msg);

		int start = lexer.getStartOfSegment("UNB");
		int end = lexer.getStartOfSegment("SRC");
		String header = msg.substring(start, end);

		start = lexer.getStartOfSegment("UNT");
		String footer = msg.substring(start);

		List<String> rv = new ArrayList<>();
		int i = 0;
		for (;;) {
			String pnr = getSinglePnr(lexer, i++);
			if (pnr == null) {
				break;
			} else {
				StringBuffer buff = new StringBuffer(lexer.getUna().getSegmentText());
				buff.append(header).append(pnr).append(footer);
				rv.add(buff.toString());
			}
		}

		return rv;
	}

	private static <T> T safeGet(List<T> list, int i) {
		if (i < 0 || i >= list.size()) {
			return null;
		}
		return list.get(i);
	}

	private static final String[] SUFFIXES = { "JR", "SR"};
	private static final String[] PREFIXES = { "MR", "MRS", "MS", "DR", "MISS", "SIR", "MADAM", "MAYOR", "PRESIDENT" };

	private static void processNames(PassengerVo p, String last, String first, String middle, String gender) {
		p.setFirstName(first);
		p.setMiddleName(middle);
		p.setLastName(last);

		if (first != null) {
			for (String prefix : PREFIXES) {
				String firstName = null;
				if (first.startsWith(prefix)) {
					firstName = first.substring(prefix.length()).trim();
				} else if (first.endsWith(prefix)) {
					firstName = first.substring(0, first.length() - prefix.length()).trim();
				}

				if (firstName != null) {
					p.setTitle(prefix);
					p.setFirstName(firstName);
					if (StringUtils.isNotEmpty(gender) && "F".equalsIgnoreCase(gender)
							&& "MR".equalsIgnoreCase(prefix)) {
						continue;
					} else {
						break;
					}

				}
			}
		}

		if (last != null) {
			for (String suffix : SUFFIXES) {
				if (last.endsWith(suffix)) {
					p.setSuffix(suffix);
					String lastName = last.substring(0, last.length() - suffix.length()).trim();
					p.setLastName(lastName);
					break;
				}
			}
		}
	}

	private static List<String> splitSsrFreeText(SSR ssr) {
		if (ssr.getFreeText() != null) {
			List<String> strs = new ArrayList<>();
			for (String s : ssr.getFreeText().split("/")) {
				strs.add(s.trim());
			}
			return strs;
		}
		return null;
	}
	
	public static String getBagTagFromElement(String tagNumber,int counter){
		if(StringUtils.isBlank(tagNumber)){
			return "0";
		}
		String tagId="";
		tagNumber=tagNumber.replaceAll("\\s", "").trim();
		Long value;
		try {
			value = (Long.valueOf(tagNumber))+counter;
			tagId=value.toString();
		} catch (NumberFormatException e) {
			tagId=tagNumber+counter;
		}
		return tagId;
	}
	public static PassengerVo getPaxFromTIF(TIF tif,List<PassengerVo> passengers){
		PassengerVo thePax = passengers.get(0);
        if (tif != null) {
            // try finding pax based on tif info
            String surname = tif.getTravelerSurname();
            List<TravelerDetails> td = tif.getTravelerDetails();
            if (CollectionUtils.isNotEmpty(td)) {
                String firstName = td.get(0).getTravelerGivenName();
                for (PassengerVo pax : passengers) {
                    if (surname.equals(pax.getLastName()) && firstName.equals(pax.getFirstName())) {
                        thePax = pax;
                        break;
                    }
                }
            }
        }
        return thePax;
	}
	private static void captureMissingInfoFromSSRs(PassengerVo pvo,List<SSR> ssrDocs){
		if(StringUtils.isBlank(pvo.getCitizenshipCountry())){
			for (SSR ssr : ssrDocs) {
				String ssrText = ssr.getFreeText();
				List<String> strs = splitSsrFreeText(ssr);
				if (CollectionUtils.isEmpty(strs)) {
					continue;
				}
				if (StringUtils.isNotEmpty(safeGet(strs, 1))
						|| (StringUtils.isEmpty(safeGet(strs, 1)) && StringUtils.isEmpty(safeGet(strs, 2)))) {
					pvo.setCitizenshipCountry(safeGet(strs, 4));
					
				}else if (StringUtils.isEmpty(safeGet(strs, 1)) && StringUtils.isNotEmpty(safeGet(strs, 2))
						&& StringUtils.isNotEmpty(safeGet(strs, 4)) ){
					pvo.setCitizenshipCountry(safeGet(strs, 5));
				}
			}
		}
	}
	public static String getPhoneNumberFromLTS(String phoneText){
		try {
			phoneText=phoneText.replaceAll("\\s+", "");
			phoneText=phoneText.substring(phoneText.indexOf("APM")+3,phoneText.length());
			if(phoneText.indexOf("/") >0){
				phoneText=phoneText.substring(0,phoneText.indexOf("/"));
			}
		} catch (Exception e) {
			//e.getMessage();
		}
		return phoneText;
	}
    public static String getFrequentFlyertextFromFreeText(String freeText){
    	String temp=null;
    	String[] tokens=freeText.split("/");
    	for(String s:tokens){
    		s.trim();
    		if(s.indexOf("-") > 0){
    			temp=s.substring(0, s.indexOf("-"));
    		}
    	}
    	return temp;
    }
    public static String getPhoneNumberFromFreeText(String freeText){
    	String temp=null;
    	String[] tokens=freeText.split("/");
    	for(String s:tokens){
    		s.trim();
    		if(s.indexOf("-") > 0){
    			temp=s.substring(0, s.indexOf("-"));
    		}
    	}
    	return temp;
    }    

}
