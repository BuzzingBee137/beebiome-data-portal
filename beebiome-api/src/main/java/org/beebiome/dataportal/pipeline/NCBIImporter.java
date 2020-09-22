package org.beebiome.dataportal.pipeline;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.beebiome.dataportal.api.core.model.FileInfo;
import org.beebiome.dataportal.api.repository.dt.BiosamplePackageTO;
import org.beebiome.dataportal.api.repository.dt.ExperimentTO;
import org.beebiome.dataportal.api.repository.dt.GeoLocationTO;
import org.beebiome.dataportal.api.repository.dt.ImportTO;
import org.beebiome.dataportal.api.repository.dt.ProjectTO;
import org.beebiome.dataportal.api.repository.dt.ProjectToPublicationTO;
import org.beebiome.dataportal.api.repository.dt.ProjectToSampleTO;
import org.beebiome.dataportal.api.repository.dt.PublicationTO;
import org.beebiome.dataportal.api.repository.dt.SampleTO;
import org.beebiome.dataportal.api.repository.dt.SampleToExperimentTO;
import org.beebiome.dataportal.api.repository.dt.SampleToNucleotideTO;
import org.beebiome.dataportal.api.repository.dt.SpeciesTO;
import org.beebiome.dataportal.api.repository.dt.SpeciesToNameTO;
import org.beebiome.dataportal.api.repository.dt.TaxonTO;
import org.beebiome.dataportal.pipeline.ncbi.bioproject.DocumentSummary;
import org.beebiome.dataportal.pipeline.ncbi.bioproject.Project;
import org.beebiome.dataportal.pipeline.ncbi.bioproject.RecordSet;
import org.beebiome.dataportal.pipeline.ncbi.bioproject.TypePublication;
import org.beebiome.dataportal.pipeline.ncbi.bioproject.TypeSubmission;
import org.beebiome.dataportal.pipeline.ncbi.biosample.AttributeType;
import org.beebiome.dataportal.pipeline.ncbi.biosample.BioSampleSetType;
import org.beebiome.dataportal.pipeline.ncbi.biosample.BioSampleType;
import org.beebiome.dataportal.pipeline.ncbi.sra.ExperimentPackageSet;
import org.beebiome.dataportal.pipeline.ncbi.sra.ExperimentPackageType;
import org.beebiome.dataportal.pipeline.ncbi.sra.LibraryDescriptorType;
import org.beebiome.dataportal.pipeline.ncbi.sra.PlatformType;
import org.beebiome.dataportal.pipeline.ncbi.taxonomy.TaxaSetType;
import org.beebiome.dataportal.pipeline.ncbi.taxonomy.TaxonType;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.JAXBIntrospector;
import javax.xml.bind.Unmarshaller;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class NCBIImporter {

    private final static Logger log = LogManager.getLogger(NCBIImporter.class.getName());

    public static void main(String[] args) throws JAXBException, IOException {
        log.traceEntry("Parameter: {}", (Object[]) args);

        int expectedArgLength = 1;
        if (args.length != expectedArgLength) {
            throw log.throwing(new IllegalArgumentException(
                    "Incorrect number of arguments provided, expected " + expectedArgLength + " arguments, "
                            + args.length + " provided. Arguments must be:" +
                            "- path to the input directory containing all XML files"));
        }

        Set<FileInfo> fileInfos = new HashSet<>();
        final File folder = new File(args[0]);
        for (final File f : folder.listFiles()) {
            fileInfos.add(new FileInfo(f.getName(), new FileInputStream(f)));
        }

        NCBIImporter importer = new NCBIImporter();
        importer.importData(fileInfos);

        log.traceExit();
    }

    public ImportTO importData(Set<FileInfo> fileInfos) throws JAXBException, IOException {
        log.traceEntry("Parameters: {}", fileInfos);
        
        Set<InputStream> bioprojectFiles = new HashSet<>();
        Set<InputStream> biosampleFiles = new HashSet<>();
        Set<InputStream> sraFiles = new HashSet<>();
        Set<InputStream> taxonomyFiles = new HashSet<>();

        for (FileInfo f : fileInfos) {
            if (f.getName().matches(".*_bioproject\\.*[0-9]*\\.xml")) {
                bioprojectFiles.add(f.getInputStream());
            } else if (f.getName().matches(".*_biosample\\.*[0-9]*\\.xml")) {
                biosampleFiles.add(f.getInputStream());
            } else if (f.getName().matches(".*_sra\\.*[0-9]*\\.xml")) {
                sraFiles.add(f.getInputStream());
            } else if (f.getName().matches(".*_taxonomy\\.*[0-9]*\\.xml")) {
                taxonomyFiles.add(f.getInputStream());
            }
        }
        NCBIImporter importer = new NCBIImporter();
        return log.traceExit(importer.importData(bioprojectFiles, biosampleFiles, sraFiles, taxonomyFiles));
    }

    public ImportTO importData(Set<InputStream> bioProjectXMLFiles, Set<InputStream> bioSampleXMLFiles,
                               Set<InputStream> sraXMLFiles, Set<InputStream> taxonomyXMLFiles)
            throws JAXBException, IOException {
        log.traceEntry("Parameters: {}, {}, {}, {}",
                bioProjectXMLFiles, bioSampleXMLFiles, sraXMLFiles, taxonomyXMLFiles);

        log.info("Start parsing of XML files...");

        RecordSet recordSet = readBioproject(bioProjectXMLFiles);

        BioSampleSetType bioSampleSet = readBiosample(bioSampleXMLFiles);

        ExperimentPackageSet experimentPackageSet = readSra(sraXMLFiles);

        TaxaSetType taxaSet = readTaxonomy(taxonomyXMLFiles);

        log.info("Done parsing of XML files.");

        log.info("Start converting data...");

        // Convert data from NCBI objects to Beebiome objects
        Set<SpeciesToNameTO> speciesToNameTOs = new HashSet<>();
        Map<Integer, SpeciesTO> speciesTOs = new HashMap<>();
        Set<TaxonTO> taxonTOs = new HashSet<>();
        for (TaxonType taxon : taxaSet.getTaxon()) {
            int parentTaxonId = taxon.getParentTaxId();
            Integer parentSpeciesId = null;
            if ("subspecies".equals(taxon.getRank()) || "strain".equals(taxon.getRank())) {
                parentSpeciesId = parentTaxonId;
                parentTaxonId = taxon.getLineageEx().getTaxon()
                        .get(taxon.getLineageEx().getTaxon().size() - 2).getTaxId();
            }

            SpeciesTO speciesTO = new SpeciesTO(taxon.getTaxId(), taxon.getScientificName(),
                    parentTaxonId, parentSpeciesId, true);
            speciesTOs.put(speciesTO.getId(), speciesTO);

            speciesToNameTOs.add(new SpeciesToNameTO(taxon.getTaxId(), taxon.getScientificName()));

            if (taxon.getOtherNames() != null) {
                if (taxon.getOtherNames().getCommonName() != null) {
                    speciesToNameTOs.add(
                            new SpeciesToNameTO(taxon.getTaxId(), taxon.getOtherNames().getCommonName()));
                }
                if (taxon.getOtherNames().getGenbankCommonName() != null) {
                    speciesToNameTOs.add(
                            new SpeciesToNameTO(taxon.getTaxId(), taxon.getOtherNames().getGenbankCommonName()));
                }
                if (taxon.getOtherNames().getSynonym() != null) {
                    speciesToNameTOs.add(
                            new SpeciesToNameTO(taxon.getTaxId(), taxon.getOtherNames().getSynonym()));
                }
            }
            taxonTOs.addAll(taxon.getLineageEx().getTaxon().stream()
                    .filter(t -> !"species".equals(t.getRank()))
                    .map(t -> new TaxonTO(t.getTaxId(), t.getScientificName()))
                    .collect(Collectors.toSet()));
        }

        Set<SampleTO> sampleTOs = new HashSet<>();
        Set<GeoLocationTO> geoLocationTOs = new HashSet<>();
        Set<BiosamplePackageTO> biosamplePackageTOs = new HashSet<>();
        Set<String> rejectedHost = new HashSet<>();
        Set<String> severalSpeciesHosts = new HashSet<>();
        Set<String> rejectedBiosamples = new HashSet<>();
        for (BioSampleType bioSample : bioSampleSet.getBioSample()) {
            Integer hostSpeciesId = null;
            String geoLocName = null;
            String latitudeLongitude = null;
            String collectionDate = null;
            for (AttributeType attribute : bioSample.getAttributes().getAttribute()) {
                if ("Not applicable".equalsIgnoreCase(attribute.getValue())) {
                    continue;
                }
                switch (attribute.getAttributeName()) {
                    case "host":
                        String host = attribute.getValue();
                        Set<SpeciesToNameTO> snTOs = speciesToNameTOs.stream()
                                .filter(to -> to.getName() != null && to.getName().equalsIgnoreCase(host))
                                .collect(Collectors.toSet());
                        if (snTOs.size() > 1) {
                            severalSpeciesHosts.add(host);

                        } else if (snTOs.size() == 1) {
                            hostSpeciesId = snTOs.stream().findAny().get().getSpeciesId();
                        } else {
                            rejectedHost.add(host);
                        }
                        break;
                    case "geo_loc_name":
                        geoLocName = attribute.getValue();
                        break;
                    case "lat_lon":
                        latitudeLongitude = attribute.getValue();
                        break;
                    case "collection_date":
                        collectionDate = getLocalDate(attribute.getValue());
                        break;
                }
            }
            if (hostSpeciesId == null) {
                rejectedBiosamples.add(bioSample.getAccession());
                continue;
            }

            String geoLocationId = latitudeLongitude;
            if (geoLocationId == null) {
                geoLocationId = geoLocName;
            }
            if (geoLocationId != null) {
                geoLocationTOs.add(new GeoLocationTO(geoLocationId, null, null, geoLocName));
            }

            Integer orgId = bioSample.getDescription().getOrganism().getTaxonomyId();
            SpeciesTO speciesTO = speciesTOs.get(orgId);
            if (speciesTO == null) {
                speciesTOs.put(orgId, new SpeciesTO(orgId,
                        bioSample.getDescription().getOrganism().getTaxonomyName(), null, null, false));
            }
            biosamplePackageTOs.add(new BiosamplePackageTO(bioSample.getPackage().getValue(),
                    bioSample.getPackage().getDisplayName()));

            sampleTOs.add(new SampleTO(
                    bioSample.getAccession(),
                    bioSample.getPackage().getValue(),
                    geoLocationId,
                    bioSample.getDescription().getOrganism().getTaxonomyId(),
                    hostSpeciesId,
                    collectionDate));
        }
        log.debug(rejectedHost.size() + " rejected hosts");
        log.trace("Rejected hosts: " + String.join(", ", rejectedHost));
        log.debug(severalSpeciesHosts.size() + " hosts mapping several species");
        log.trace("Hosts mapping several species: " + String.join(", ", severalSpeciesHosts));
        log.debug(rejectedBiosamples.size() + " rejected BioSamples due to absent or unknown host attribute");
        log.trace("Rejected BioSamples due to absent or unknown host attribute: " + String.join(", ", rejectedBiosamples));

        Set<String> validSampleAccs = sampleTOs.stream()
                .map(SampleTO::getBiosampleAcc)
                .collect(Collectors.toSet());

        Set<ProjectToSampleTO> projectToSampleTOs = new HashSet<>();
        Set<SampleToExperimentTO> sampleToExperimentTOs = new HashSet<>();
        Set<ExperimentTO> experimentTOs = new HashSet<>();
        Set<String> rejectedExperiments = new HashSet<>();
        for (ExperimentPackageType experiment : experimentPackageSet.getExperimentPackages()) {
            Set<String> biosampleAccs = experiment.getSampleType().getIDENTIFIERS().getEXTERNALID().stream()
                    .filter(id -> "BioSample".equals(id.getNamespace()))
                    .map(id -> id.getValue())
                    .collect(Collectors.toSet());

            if (biosampleAccs.stream().noneMatch(validSampleAccs::contains)) {
                rejectedExperiments.add(experiment.getExperimentType().getAccession());
                continue;
            }

            PlatformType platform = experiment.getExperimentType().getPLATFORM();
            String platformName = null;
            if (platform.getLS454() != null) {
                platformName = platform.getLS454().getINSTRUMENTMODEL();
            } else if (platform.getILLUMINA() != null) {
                platformName = platform.getILLUMINA().getINSTRUMENTMODEL().value();
            } else if (platform.getHELICOS() != null) {
                platformName = platform.getHELICOS().getINSTRUMENTMODEL().value();
            } else if (platform.getABISOLID() != null) {
                platformName = platform.getABISOLID().getINSTRUMENTMODEL().value();
            } else if (platform.getCOMPLETEGENOMICS() != null) {
                platformName = platform.getCOMPLETEGENOMICS().getINSTRUMENTMODEL().value();
            } else if (platform.getBGISEQ() != null) {
                platformName = platform.getBGISEQ().getINSTRUMENTMODEL().value();
            } else if (platform.getOXFORDNANOPORE() != null) {
                platformName = platform.getOXFORDNANOPORE().getINSTRUMENTMODEL().value();
            } else if (platform.getPACBIOSMRT() != null) {
                platformName = platform.getPACBIOSMRT().getINSTRUMENTMODEL().value();
            } else if (platform.getIONTORRENT() != null) {
                platformName  =platform.getIONTORRENT().getINSTRUMENTMODEL().value();
            } else if (platform.getCAPILLARY() != null) {
                platformName = platform.getCAPILLARY().getINSTRUMENTMODEL().value();
            }

            LibraryDescriptorType libraryDescriptor = experiment.getExperimentType().getDESIGN().getLIBRARYDESCRIPTOR();

            String libraryLayout = null;
            if (libraryDescriptor.getLIBRARYLAYOUT().getSINGLE() != null) {
                libraryLayout = "SINGLE";
            } else if (libraryDescriptor.getLIBRARYLAYOUT().getPAIRED() != null) {
                libraryLayout = "PAIRED";
            } else {
                log.debug("Layout not defined for " + experiment.getExperimentType().getAccession());
            }

            ExperimentTO experimentTO = new ExperimentTO(
                    experiment.getExperimentType().getAccession(),
                    experiment.getExperimentType().getTITLE(),
                    platformName,
                    libraryDescriptor.getLIBRARYSTRATEGY().value(),
                    libraryLayout,
                    libraryDescriptor.getLIBRARYSOURCE().value());
            experimentTOs.add(experimentTO);

            Set<String> bioProjectAccs = experiment.getStudyType().getIDENTIFIERS().getEXTERNALID().stream()
                    .filter(id -> "BioProject".equals(id.getNamespace()))
                    .map(id -> id.getValue())
                    .collect(Collectors.toSet());
            for (String biosampleAcc : biosampleAccs) {
                for (String bioProjectAcc : bioProjectAccs) {
                    projectToSampleTOs.add(new ProjectToSampleTO(bioProjectAcc, biosampleAcc));
                }
                sampleToExperimentTOs.add(new SampleToExperimentTO(biosampleAcc, experimentTO.getSraAcc()));
            }
        }
        log.debug(rejectedExperiments.size() + " rejected experiments due to the absence of valid BioSampleAcc");
        log.debug("Rejected experiments due to the absence of valid BioSampleAcc: " + String.join(", ", rejectedExperiments));


        Set<String> validBioProjectAccs = projectToSampleTOs.stream()
                .map(ProjectToSampleTO::getBioprojectAcc)
                .collect(Collectors.toSet());
        Set<SampleToNucleotideTO> sampleToNucleotideTOs = new HashSet<>();

        Set<ProjectTO> projectTOs = new HashSet<>();
        Set<PublicationTO> publicationTOs = new HashSet<>();
        Set<ProjectToPublicationTO> projectToPublicationTOs = new HashSet<>();
        Set<String> rejectedProjects = new HashSet<>();
        for (DocumentSummary documentSummary : recordSet.getDocumentSummaries()) {
            Project p = documentSummary.getProject();
            String bioprojectAcc = p.getProjectID().getArchiveID().getAccession();
            if (!validBioProjectAccs.contains(bioprojectAcc)) {
                rejectedProjects.add(bioprojectAcc);
                continue;
            }
            TypeSubmission s = documentSummary.getSubmission();
            List<Project.ProjectDescr.Grant> grant = p.getProjectDescr().getGrant();

            LocalDate submittedDate = getLocalDate(s.getSubmitted());
            LocalDate lastUpdateDate = getLocalDate(s.getLastUpdate());

            projectTOs.add(new ProjectTO(bioprojectAcc,
                    p.getProjectDescr().getTitle(),
                    p.getProjectDescr().getDescription(),
                    submittedDate,
                    lastUpdateDate,
                    s.getDescription().getOrganization().stream()
                            .map(o -> o.getName().getValue())
                            .collect(Collectors.joining(", ")),
                    grant.stream().map(g -> g.getGrantId()).collect(Collectors.joining(", ")),
                    grant.stream().map(g -> g.getTitle()).collect(Collectors.joining(", ")),
                    grant.stream().map(g -> g.getAgency().getAbbr()).collect(Collectors.joining(", "))));

            for (TypePublication pub : p.getProjectDescr().getPublication()) {
                publicationTOs.add(new PublicationTO(pub.getId(), pub.getDbType()));
                projectToPublicationTOs.add(new ProjectToPublicationTO(bioprojectAcc, pub.getId()));
            }
        }
        log.debug(rejectedProjects.size() + " rejected projects due to the absence in samples");
        log.trace("Rejected projects due to the absence in samples: " + String.join(", ", rejectedProjects));

        log.debug("speciesToNameTOs: " + speciesToNameTOs.size());
        log.debug("speciesTOs: " + speciesTOs.size());
        log.debug("taxonTOs: " + taxonTOs.size());
        log.debug("sampleTOs: " + sampleTOs.size());
        log.debug("geoLocationTOs: " + geoLocationTOs.size());
        log.debug("biosamplePackageTOs: " + biosamplePackageTOs.size());
        log.debug("projectToSampleTOs " + projectToSampleTOs.size());
        log.debug("sampleToExperimentTOs: " + sampleToExperimentTOs.size());
        log.debug("experimentTOs: " + experimentTOs.size());
        log.debug("projectTOs: " + projectTOs.size());
        log.debug("publicationTOs: " + publicationTOs.size());
        log.debug("projectToPublicationTOs: " + projectToPublicationTOs.size());

        log.info("Done converting data...");

        return log.traceExit(new ImportTO(taxonTOs, speciesTOs.values(), speciesToNameTOs, geoLocationTOs,
                publicationTOs, projectTOs, projectToPublicationTOs, biosamplePackageTOs,
                //                recommendationTOs,
                sampleTOs, projectToSampleTOs,
                //                sampleToRecommendationTOs, sampleToNucleotide, 
                experimentTOs, sampleToExperimentTOs));
    }

    protected String getLocalDate(String inputDate) {
        log.traceEntry("Parameter: {}", inputDate);

        DateTimeFormatter formatterYMD = getDateFormatter("yyyy-MM-dd");
        DateTimeFormatter formatterYM = getDateFormatter("yyyy-MM");
        DateTimeFormatter formatterY = getDateFormatter("yyyy");

        String outputDate = getStringFromLocalDate(inputDate, getDateFormatter("dd-MMM-yyyy"), formatterYMD);
        if (outputDate != null)
            return log.traceExit(outputDate);
        outputDate = getStringFromLocalDate(inputDate, getDateFormatter("yyyy-MM-dd"), formatterYMD);
        if (outputDate != null)
            return log.traceExit(outputDate);
        outputDate = getStringFromYearMonth(inputDate, getDateFormatter("yyyy-MM"), formatterYM);
        if (outputDate != null)
            return log.traceExit(outputDate);
        outputDate = getStringFromYearMonth(inputDate, getDateFormatter("MM-yyyy"), formatterYM);
        if (outputDate != null)
            return log.traceExit(outputDate);
        outputDate = getStringFromYearMonth(inputDate, getDateFormatter("MMM-yyyy"), formatterYM);
        if (outputDate != null)
            return log.traceExit(outputDate);
        outputDate = getStringFromYearMonth(inputDate, getDateFormatter("MMMM-yyyy"), formatterYM);
        if (outputDate != null)
            return log.traceExit(outputDate);
        return log.traceExit(getStringFromYear(inputDate, formatterY));
    }

    private String getStringFromLocalDate(String stringDate, DateTimeFormatter inputFormatter, DateTimeFormatter outputFormatter) {
        log.traceEntry("Parameters: {}, {}, {}", stringDate, inputFormatter, outputFormatter);
        String s = null;
        try {
            s = LocalDate.parse(stringDate, inputFormatter).format(outputFormatter);
        } catch (DateTimeParseException ignored) {
        }
        return log.traceExit(s);
    }
    
    private String getStringFromYearMonth(String stringDate, DateTimeFormatter inputFormatter, DateTimeFormatter outputFormatter) {
        log.traceEntry("Parameters: {}, {}, {}", stringDate, inputFormatter, outputFormatter);
        String s = null;
        try {
            s = YearMonth.parse(stringDate, inputFormatter).format(outputFormatter);
        } catch (DateTimeParseException ignored) {
        }
        return log.traceExit(s);
    }
    
    private String getStringFromYear(String stringDate, DateTimeFormatter outputFormatter) {
        log.traceEntry("Parameters: {}, {}, {}", stringDate, outputFormatter);
        String s = null;
        try {
            s = Year.parse(stringDate).format(outputFormatter);
        } catch (DateTimeParseException ignored) {
        }
        return log.traceExit(s);
    }

    private DateTimeFormatter getDateFormatter(String pattern) {
        log.traceEntry("Parameters: {}", pattern);

        return log.traceExit(new DateTimeFormatterBuilder()
                // case insensitive to parse JAN and FEB
                .parseCaseInsensitive()
                // add pattern
                .appendPattern(pattern)
                // create formatter (use English Locale to parse month names)
                .toFormatter(Locale.ENGLISH));
    }

    private LocalDate getLocalDate(XMLGregorianCalendar date) {
        log.traceEntry("Parameter: {}", date);

        LocalDate localDate = null;
        if (date != null) {
            localDate = LocalDate.of(date.getYear(), date.getMonth(), date.getDay());
        }
        return log.traceExit(localDate);
    }

    private <T, U> Set<U> read(Set<InputStream> files, Class<T> cls, Function<T, List<U>> func)
            throws JAXBException, IOException {
        log.traceEntry("Parameters: {}, {}, {}", files, cls, func);

        JAXBContext context = JAXBContext.newInstance(cls);
        Unmarshaller unmarshaller = context.createUnmarshaller();
        Set<U> set = new HashSet<>();
        for (InputStream file : files) {
            if (file.available() > 0) {
                T r = (T) JAXBIntrospector.getValue(unmarshaller.unmarshal(file));
                set.addAll(func.apply(r));
            }
        }
        log.debug(set.size() + " parsed " + cls.getName());

        return log.traceExit(set);
    }

    private RecordSet readBioproject(Set<InputStream> bioprojectXmlFiles) throws JAXBException, IOException {
        log.traceEntry("Parameter: {}", bioprojectXmlFiles);

        Set<DocumentSummary> docSums = read(bioprojectXmlFiles, RecordSet.class, RecordSet::getDocumentSummaries);
        RecordSet set = new RecordSet();
        set.setDocumentSummaries(new ArrayList<>(docSums));

        return log.traceExit(set);
    }

    private BioSampleSetType readBiosample(Set<InputStream> biosampleXmlFiles) throws JAXBException, IOException {
        log.traceEntry("Parameter: {}", biosampleXmlFiles);

        Set<BioSampleType> biosamples = read(biosampleXmlFiles, BioSampleSetType.class,
                BioSampleSetType::getBioSample);
        BioSampleSetType set = new BioSampleSetType();
        set.setBioSample(new ArrayList<>(biosamples));

        return log.traceExit(set);
    }

    private ExperimentPackageSet readSra(Set<InputStream> sraXmlFiles) throws JAXBException, IOException {
        log.traceEntry("Parameter: {}", sraXmlFiles);

        Set<ExperimentPackageType> experimentPackageTypes = read(sraXmlFiles, ExperimentPackageSet.class,
                ExperimentPackageSet::getExperimentPackages);
        ExperimentPackageSet set = new ExperimentPackageSet();
        set.setExperimentPackages(new ArrayList<>(experimentPackageTypes));

        return log.traceExit(set);
    }

    private TaxaSetType readTaxonomy(Set<InputStream>  taxonomyXmlFiles) throws JAXBException, IOException {
        log.traceEntry("Parameter: {}", taxonomyXmlFiles);

        Set<TaxonType> taxonTypes = read(taxonomyXmlFiles,
                TaxaSetType.class, TaxaSetType::getTaxon);
        TaxaSetType set = new TaxaSetType();
        set.setTaxon(new ArrayList<>(taxonTypes));

        return log.traceExit(set);
    }
}
